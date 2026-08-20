package tech.wenisch.petri.gateway;

import java.util.List;
import java.util.Map;

/**
 * The agent gateway, as Petri needs it.
 *
 * <p>Deliberately narrow. Petri holds no repository credential and cannot push:
 * cloning, branching, secret scanning, protected-path checks and pushing all
 * live behind this boundary. Widening it would move the dangerous parts into the
 * orchestrator, which is the one thing this design is trying not to do.
 *
 * <p>Starting is asynchronous by contract. Blocking a call for the length of a
 * turn leaves no channel to ask whether anything is still alive, because the
 * only channel is busy carrying the answer.
 */
public interface AgentGateway {

    /** Begin a turn and return its session id. Returns as soon as it is accepted. */
    String start(StartRequest request);

    /** Current state of the given sessions, keyed by session id. */
    Map<String, SessionSnapshot> observe(List<String> sessionIds);

    /** Stop a session. Best effort: a session that has already finished is not an error. */
    void abort(String sessionId);
}
