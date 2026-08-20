package tech.wenisch.petri.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
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

    public LivenessService(AgentRunRepository runs,
                           AgentGateway gateway,
                           GatewayProperties properties,
                           GateRegistry gates,
                           TransitionService transitions) {
        this.runs = runs;
        this.gateway = gateway;
        this.properties = properties;
        this.gates = gates;
        this.transitions = transitions;
    }

    @Scheduled(fixedDelayString = "${petri.liveness.interval:PT10S}")
    public void poll() {
        try {
            observeOpenRuns();
        } catch (RuntimeException ex) {
            LOG.error("Liveness cycle failed", ex);
        }
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
        if (snapshot != null && snapshot.lastEventAt() != null) {
            run.setLastEventAt(snapshot.lastEventAt());
        }

        SessionState state = snapshot == null ? SessionState.UNKNOWN : snapshot.state();
        switch (state) {
            case IDLE -> finish(run, RunStatus.SUCCEEDED, "session went idle", now);
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
        runs.save(run);
        LOG.info("Run {} finished: {} ({})", run.getId(), status, reason);
        decide(run);
    }

    /** Ask the state's gate what happens now, and act on it. */
    private void decide(AgentRun run) {
        Card card = run.getCard();
        WorkflowState state = run.getState();

        GateOutcome outcome = gates.evaluate(state.getGate(), card, run);
        switch (outcome.decision()) {
            case PASS -> {
                if (state.getNextOnPass() == null) {
                    LOG.info("Card {} passed {} with nowhere to go", card.getId(), state.getName());
                    return;
                }
                transitions.move(card, state.getNextOnPass(),
                        actor(state), outcome.reason(), run);
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
