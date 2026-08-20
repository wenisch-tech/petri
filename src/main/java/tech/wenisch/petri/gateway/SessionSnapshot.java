package tech.wenisch.petri.gateway;

import java.time.Instant;

/**
 * One observation of a session.
 *
 * @param sessionId    session the observation is about
 * @param state        what the gateway says it is doing
 * @param lastEventAt  when it last produced anything, or null if never
 * @param detail       free text, e.g. the reason behind a RETRY
 */
public record SessionSnapshot(
        String sessionId,
        SessionState state,
        Instant lastEventAt,
        String detail) {

    public static SessionSnapshot unknown(String sessionId) {
        return new SessionSnapshot(sessionId, SessionState.UNKNOWN, null, null);
    }
}
