package tech.wenisch.petri.dto;

import java.time.Instant;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.RunStatus;

/** One run of a state's action, flattened inside the transaction. */
public record RunView(
        int attempt,
        RunStatus status,
        String sessionId,
        Instant startedAt,
        Instant lastEventAt,
        Instant finishedAt) {

    public static RunView of(AgentRun run) {
        return new RunView(
                run.getAttempt(),
                run.getStatus(),
                run.getSessionId(),
                run.getStartedAt(),
                run.getLastEventAt(),
                run.getFinishedAt());
    }
}
