package tech.wenisch.petri.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.RunStatus;
import tech.wenisch.petri.entity.WorkflowState;
import tech.wenisch.petri.gate.GateOutcome;
import tech.wenisch.petri.gate.GateRegistry;
import tech.wenisch.petri.gateway.AgentGateway;
import tech.wenisch.petri.gateway.GatewayProperties;
import tech.wenisch.petri.gateway.SessionSnapshot;
import tech.wenisch.petri.gateway.SessionState;

/**
 * Watches open runs, and decides what happens when one ends.
 *
 * <p>A run is bounded by <em>silence</em>, not by elapsed time. The gateway
 * reports BUSY for the whole of a single model call, and a call on a contended
 * GPU can legitimately take minutes, so elapsed time never distinguishes working
 * from hung. Time since the last observed event does.
 *
 * <p>Like the runner, this holds no transaction of its own. One cycle makes four
 * kinds of network call - observing sessions, reading a transcript, asking a
 * reviewing model for a verdict, and opening a pull request - and a reviewing
 * model can take minutes. Every database write around them is a short
 * transaction in {@link RunLedger} or {@link TransitionService}; nothing here
 * pins a connection while waiting on something else.
 */
@Service
public class LivenessService {

    private static final Logger LOG = LoggerFactory.getLogger(LivenessService.class);

    private final RunLedger ledger;
    private final AgentGateway gateway;
    private final GatewayProperties properties;
    private final GateRegistry gates;
    private final TransitionService transitions;
    private final PublishService publisher;
    private final PetriMetrics metrics;

    public LivenessService(RunLedger ledger,
                           AgentGateway gateway,
                           GatewayProperties properties,
                           GateRegistry gates,
                           TransitionService transitions,
                           PublishService publisher,
                           PetriMetrics metrics) {
        this.ledger = ledger;
        this.gateway = gateway;
        this.properties = properties;
        this.gates = gates;
        this.transitions = transitions;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    public void observeOpenRuns() {
        List<RunLedger.OpenRun> open = ledger.openRunViews();
        if (open.isEmpty()) {
            return;
        }

        List<String> sessionIds = open.stream()
                .map(RunLedger.OpenRun::sessionId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        Map<String, SessionSnapshot> snapshots = gateway.observe(sessionIds);

        Instant now = Instant.now();
        for (RunLedger.OpenRun run : open) {
            SessionSnapshot snapshot = run.sessionId() == null
                    ? null : snapshots.get(run.sessionId());
            apply(run, snapshot, now);
        }
    }

    private void apply(RunLedger.OpenRun run, SessionSnapshot snapshot, Instant now) {
        // A run with no session id never got as far as the gateway. That happens
        // if the process dies between recording the run and starting it, and
        // nothing else can ever resolve it: there is no session to ask about.
        // Left alone it stays open forever, holding a slot and holding its card.
        if (run.sessionId() == null || run.sessionId().isBlank()) {
            if (!withinStartupGrace(run, now)) {
                finish(run, RunStatus.FAILED, "never received a session id", now);
            }
            return;
        }

        Instant lastEventAt = snapshot != null && snapshot.lastEventAt() != null
                ? snapshot.lastEventAt() : run.lastEventAt();
        RunLedger.OpenRun seen = new RunLedger.OpenRun(
                run.runId(), run.sessionId(), run.startedAt(), lastEventAt);

        SessionState state = snapshot == null ? SessionState.UNKNOWN : snapshot.state();
        switch (state) {
            case IDLE -> {
                // A run is absent from the gateway's status until it starts
                // producing, so "idle" immediately after starting means "not
                // begun yet", not "done". Concluding here would end every run
                // within seconds of creating it.
                if (withinStartupGrace(seen, now)) {
                    ledger.recordEvent(seen.runId(), lastEventAt, null);
                } else {
                    finish(seen, RunStatus.SUCCEEDED, "session went idle", now);
                }
            }
            // Still alive, and saying why it is slow. Surfacing the reason is
            // better than a card that merely looks stuck.
            case RETRY -> ledger.recordEvent(seen.runId(), lastEventAt, snapshot.detail());
            case BUSY -> enforceBounds(seen, now);
            // The gateway has no record of the session. Treat it as gone rather
            // than waiting forever on something nobody is running.
            case UNKNOWN -> finish(seen, RunStatus.FAILED, "gateway lost the session", now);
        }
    }

    private boolean withinStartupGrace(RunLedger.OpenRun run, Instant now) {
        return run.startedAt() != null
                && Duration.between(run.startedAt(), now)
                        .compareTo(properties.startupGrace()) < 0;
    }

    private void enforceBounds(RunLedger.OpenRun run, Instant now) {
        Duration silence = run.silenceFor(now);
        if (silence.compareTo(properties.idleTimeout()) > 0) {
            gateway.abort(run.sessionId());
            finish(run, RunStatus.ABORTED,
                    "produced no output for " + silence.toMinutes() + "m", now);
            return;
        }

        if (run.startedAt() != null
                && Duration.between(run.startedAt(), now).compareTo(properties.maxDuration()) > 0) {
            gateway.abort(run.sessionId());
            finish(run, RunStatus.ABORTED, "ran past the ceiling", now);
            return;
        }

        ledger.recordEvent(run.runId(), run.lastEventAt(), null);
    }

    private void finish(RunLedger.OpenRun run, RunStatus status, String reason, Instant now) {
        // Fetched once, here, rather than on every gate evaluation or page view -
        // and before the transaction that stores it, because it is an HTTP call.
        String output = run.sessionId() == null ? null : gateway.lastMessage(run.sessionId());

        ledger.finish(run.runId(), status, reason, output, now);
        metrics.runFinished(status);
        decide(run.runId());
    }

    /**
     * Ask the state's gate what happens now, and act on it.
     *
     * <p>Loaded, decided and applied in separate steps rather than one, because
     * deciding can mean asking a reviewing model, and publishing means calling
     * the forge.
     */
    private void decide(Long runId) {
        Optional<RunLedger.GateInput> loaded = ledger.gateInput(runId);
        if (loaded.isEmpty()) {
            return;
        }
        RunLedger.GateInput input = loaded.get();
        Card card = input.card();
        WorkflowState state = input.state();

        GateOutcome outcome = gates.evaluate(state.getGate(), card, input.run());
        metrics.gateEvaluated(state.getGate().name(), outcome.decision());

        switch (outcome.decision()) {
            case PASS -> {
                if (state.getNextOnPass() == null) {
                    LOG.info("Card {} passed {} with nowhere to go", card.getId(), state.getName());
                    return;
                }
                WorkflowState target = state.getNextOnPass();
                transitions.move(card, target, actor(state), outcome.reason(), input.run());

                // Publishing happens on arrival, so the state that opens the
                // pull request is named in the pipeline rather than inferred
                // from being last.
                publish(input, target);
            }
            case FAIL -> {
                if (state.getNextOnFail() == null || state.getNextOnFail().equals(state)) {
                    // Staying put is the common case: the card is retried here
                    // until it runs out of attempts, which is what makes "stuck
                    // in implement" distinguishable from "never left planner".
                    LOG.info("Card {} failed {}: {}", card.getId(), state.getName(), outcome.reason());
                    return;
                }
                transitions.move(card, state.getNextOnFail(),
                        actor(state), outcome.reason(), input.run());
            }
            case HOLD -> LOG.info("Card {} held in {}: {}",
                    card.getId(), state.getName(), outcome.reason());
        }
    }

    private void publish(RunLedger.GateInput input, WorkflowState target) {
        Card card = input.card();
        PublishService.Published published = publisher.publish(card, target.isPublish());
        if (published == null) {
            return;
        }
        // The card is detached out here, so the pull request URL is stored
        // explicitly rather than by hoping a write to it gets flushed.
        ledger.recordPullRequest(card.getId(), published.pullRequestUrl());
        transitions.note(card, target, "publish", published.note(), input.run());
    }

    private String actor(WorkflowState state) {
        return state.getModelAlias() == null ? state.getGate().name().toLowerCase() : state.getModelAlias();
    }
}
