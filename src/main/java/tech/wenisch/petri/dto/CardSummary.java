package tech.wenisch.petri.dto;

import java.time.Duration;
import java.time.Instant;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.RunStatus;

/**
 * A card as it appears on the board, with the liveness of its latest run.
 *
 * <p>Status alone is not enough to render honestly: a run reports RUNNING for
 * the whole of a single model call, which can legitimately last minutes. The
 * board therefore shows how long the agent has been silent next to the status,
 * so "working" and "hung" do not look identical.
 */
public record CardSummary(
        Long id,
        String title,
        String branch,
        String pullRequestUrl,
        int attempts,
        RunStatus runStatus,
        Duration silence) {

    public static CardSummary of(Card card, AgentRun latestRun, Instant now) {
        return new CardSummary(
                card.getId(),
                card.getTitle(),
                card.getBranch(),
                card.getPullRequestUrl(),
                card.getAttempts(),
                latestRun == null ? null : latestRun.getStatus(),
                latestRun == null ? null : latestRun.silenceFor(now));
    }

    public boolean isRunning() {
        return runStatus == RunStatus.RUNNING;
    }

    /** Short human form for the board, e.g. "4m". Empty when nothing has run. */
    public String silenceLabel() {
        if (silence == null) {
            return "";
        }
        long seconds = Math.max(0, silence.getSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "m";
        }
        return (seconds / 3600) + "h";
    }
}
