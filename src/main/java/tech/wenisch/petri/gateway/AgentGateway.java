package tech.wenisch.petri.gateway;

import java.util.List;
import java.util.Map;

/**
 * The agent runtime, as Petri needs it.
 *
 * <p>Four methods, and none of them mention git. The agent clones, commits and
 * pushes with its own credential; Petri names a workspace, sends a prompt, and
 * watches. Everything Petri knows about the result it learns from the forge or
 * from what the agent said.
 *
 * <p>That narrowness is the point. Anything that can run a shell and hold a
 * token can sit behind this interface, so Petri is not tied to one harness and
 * needs no filesystem in common with it.
 *
 * <p>Starting is asynchronous by contract. Blocking for the length of a turn
 * leaves nothing able to answer whether the turn is still alive, because the
 * only channel is busy carrying the answer.
 */
public interface AgentGateway {

    /** Begin a turn and return its session id, as soon as it is accepted. */
    String start(StartRequest request);

    /** Current state of the given sessions, keyed by session id. */
    Map<String, SessionSnapshot> observe(List<String> sessionIds);

    /** Stop a session. A session that has already finished is not an error. */
    void abort(String sessionId);

    /** The agent's last message in a session, or empty if it produced none. */
    String lastMessage(String sessionId);
}
