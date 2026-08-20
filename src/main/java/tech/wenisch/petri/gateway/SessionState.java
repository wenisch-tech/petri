package tech.wenisch.petri.gateway;

/**
 * What an agent session is doing, as the gateway reports it.
 *
 * <p>Note what is missing: any notion of healthy. A session reports BUSY for the
 * whole of a single model call, and such a call can legitimately run for
 * minutes, so this alone never distinguishes working from hung. The runner pairs
 * it with the time of the last observed event.
 */
public enum SessionState {
    /** Nothing in flight. For a run that had started, this means it finished. */
    IDLE,
    /** Producing. Says nothing about progress. */
    BUSY,
    /** Retrying a failed provider call. */
    RETRY,
    /** The gateway has no record of this session. */
    UNKNOWN
}
