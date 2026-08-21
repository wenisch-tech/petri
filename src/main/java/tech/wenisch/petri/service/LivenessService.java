package tech.wenisch.petri.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.RunStatus;
import tech.wenisch.petri.entity.WorkflowState;
import tech.wenisch.petri.gate.GateOutcome;
import tech.wenisch.petri.gate.GateRegistry;
import tech.wenisch.petri.gateway.AgentGateway;
import tech.wenisch.petri.gateway.GatewayProperties;
import tech.wenisch.petri.gateway.SessionSnapshot;
import tech.wenisch.petri.gateway.SessionState;
import tech.wenisch.petri.repository.AgentRunRepository;

/**
 * Watches open runs, and decides what happens when one ends.
 *
 * <p>A run is bounded by <em>silence</em>, not by elapsed time. The gateway
 * reports BUSY for the whole of a single model call, and a call on a contended
 * GPU can legitimately take minutes, so elapsed time never distinguishes working
 * from hung. Time since the last observed event does.
 */
@Service
public class LivenessService {

    private static final Logger LOG = LoggerFactory.getLogger(LivenessService.class);

    private final AgentRunRepository runs;
    private final AgentGateway gateway;
    private final GatewayProperties properties;
    private final GateRegistry gates;
    private final TransitionService transitions;
    private final PublishService publisher;
    private final PetriMetrics metrics;

    public LivenessService(AgentRunRepository runs,
                           AgentGateway gateway,
                           GatewayProperties properties,
                           GateRegistry gates,
                           TransitionService transitions,
                           PublishService publisher,
                           PetriMetrics metrics) {
        this.runs = runs;
        this.gateway = gateway;
        this.properties = properties;
        this.gates = gates;
        this.transitions = transitions;
        this.publisher = publisher;
        this.metrics = metrics;
    }


    @Transactional
    public void observeOpenRuns() {
        List<AgentRun> open = runs.findByStatusIn(List.of(RunStatus.PENDING, RunStatus.RUNNING));
        if (open.isEmpty()) {
            return;
        }

        List<String> sessionIds = open.stream()
                .map(AgentRun::getSessionId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        Map<String, SessionSnapshot> snapshots = gateway.observe(sessionIds);

        Instant now = Instant.now();
        for (AgentRun run : open) {
            SessionSnapshot snapshot = run.getSessionId() == null
                    ? null : snapshots.get(run.getSessionId());
            apply(run, snapshot, now);
        }
    }

    private void apply(AgentRun run, SessionSnapshot snapshot, Instant now) {
        // A run with no session id never got as far as the gateway. That happens
        // if the process dies between recording the run and starting it, and
        // nothing else can ever resolve it: there is no session to ask about.
        // Left alone it stays open forever and - because dispatch is serialised -
        // blocks every board on the instance. One such run, orphaned by a crash,
        // wedged a running deployment.
        if (run.getSessionId() == null || run.getSessionId().isBlank()) {
            if (!withinStartupGrace(run, now)) {
                finish(run, RunStatus.FAILED, "never received a session id", now);
            }
            return;
        }

        if (snapshot != null && snapshot.lastEventAt() != null) {
            run.setLastEventAt(snapshot.lastEventAt());
        }

        SessionState state = snapshot == null ? SessionState.UNKNOWN : snapshot.state();
        switch (state) {
            case IDLE -> {
                // A run is absent from the gateway's status until it starts
                // producing, so "idle" immediately after starting means "not
                // begun yet", not "done". Concluding here would end every run
                // within seconds of creating it.
                if (withinStartupGrace(run, now)) {
                    runs.save(run);
                } else {
                    finish(run, RunStatus.SUCCEEDED, "session went idle", now);
                }
            }
            case RETRY -> {
                // Still alive, and saying why it is slow. Surfacing the reason is
                // better than a card that merely looks stuck.
                run.setSummary(snapshot.detail());
                runs.save(run);
            }
            case BUSY -> enforceBounds(run, now);
            case UNKNOWN -> {
                // The gateway has no record of the session. Treat it as gone
                // rather than waiting forever on something nobody is running.
                if (run.getSessionId() != null) {
                    finish(run, RunStatus.FAILED, "gateway lost the session", now);
                }
            }
        }
    }

    private boolean withinStartupGrace(AgentRun run, Instant now) {
        return run.getStartedAt() != null
                && Duration.between(run.getStartedAt(), now)
                        .compareTo(properties.startupGrace()) < 0;
    }

    private void enforceBounds(AgentRun run, Instant now) {
        Duration silence = run.silenceFor(now);
        if (silence.compareTo(properties.idleTimeout()) > 0) {
            gateway.abort(run.getSessionId());
            finish(run, RunStatus.ABORTED,
                    "produced no output for " + silence.toMinutes() + "m", now);
            return;
        }

        if (run.getStartedAt() != null
                && Duration.between(run.getStartedAt(), now).compareTo(properties.maxDuration()) > 0) {
            gateway.abort(run.getSessionId());
            finish(run, RunStatus.ABORTED, "ran past the ceiling", now);
            return;
        }

        runs.save(run);
    }

    private void finish(AgentRun run, RunStatus status, String reason, Instant now) {
        run.setStatus(status);
        run.setFinishedAt(now);
        run.setSummary(reason);
        // Fetched once, here, rather than on every gate evaluation or page view.
        if (run.getSessionId() != null) {
            run.setOutput(gateway.lastMessage(run.getSessionId()));
        }
        runs.save(run);
        metrics.runFinished(status);
        LOG.info("Run {} finished: {} ({})", run.getId(), status, reason);
        decide(run);
    }

    /** Ask the state's gate what happens now, and act on it. */
    private void decide(AgentRun run) {
        Card card = run.getCard();
        WorkflowState state = run.getState();

        GateOutcome outcome = gates.evaluate(state.getGate(), card, run);
        metrics.gateEvaluated(state.getGate().name(), outcome.decision());
        switch (outcome.decision()) {
            case PASS -> {
                if (state.getNextOnPass() == null) {
                    LOG.info("Card {} passed {} with nowhere to go", card.getId(), state.getName());
                    return;
                }
                WorkflowState target = state.getNextOnPass();
                transitions.move(card, target, actor(state), outcome.reason(), run);

                // Publishing happens on arrival, so the state that opens the
                // pull request is named in the pipeline rather than inferred
                // from being last.
                String published = publisher.publish(card, target);
                if (published != null) {
                    metrics.published(card.getPullRequestUrl() != null);
                    transitions.note(card, target, "publish", published, run);
                }
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
                        actor(state), outcome.reason(), run);
            }
            case HOLD -> LOG.info("Card {} held in {}: {}",
                    card.getId(), state.getName(), outcome.reason());
        }
    }

    private String actor(WorkflowState state) {
        return state.getModelAlias() == null ? state.getGate().name().toLowerCase() : state.getModelAlias();
    }
}
