package tech.wenisch.petri.dto;

import java.time.Instant;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.RunStatus;

/**
 * One run of a state's action, flattened inside the transaction.
 *
 * <p>{@code summary} is Petri's account of how the run ended; {@code output} is
 * what the agent itself said. Both are worth showing: the first explains the
 * status, the second is the only place the agent's reasoning survives.
 */
public record RunView(
        int attempt,
        RunStatus status,
        String sessionId,
        Instant startedAt,
        Instant lastEventAt,
        Instant finishedAt,
        String summary,
        String output) {

    public static RunView of(AgentRun run) {
        return new RunView(
                run.getAttempt(),
                run.getStatus(),
                run.getSessionId(),
                run.getStartedAt(),
                run.getLastEventAt(),
                run.getFinishedAt(),
                run.getSummary(),
                run.getOutput());
    }
}
