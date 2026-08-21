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

    /**
     * Run the gateway's push gate against a branch without pushing.
     *
     * <p>Petri does not reimplement any of it. Secret scanning across every
     * commit in the range, protected paths, test detection and re-running the
     * whole gate after a rebase live on the other side of this call, where they
     * have been hardened by real failures. Asking is the whole contract.
     */
    GateReport check(String repository, String branch);

    /**
     * The change made on a branch, as a unified diff.
     *
     * <p>Needed so a reviewer can read what was actually written. Asking the
     * gateway rather than the forge keeps Petri away from the credential, and
     * means the diff reviewed is the one about to be pushed rather than whatever
     * a later fetch would return.
     */
    String diff(String repository, String branch);

    /** The agent's last message in a session, or empty if it produced none. */
    String lastMessage(String sessionId);

    /**
     * Push the branch, having passed the gate.
     *
     * <p>Petri never holds the credential and never runs git: this asks the
     * gateway to do it, and the gateway re-runs its own gate on the rebased
     * result before it does. A refusal comes back as a failed report rather than
     * an exception, because it is an answer.
     */
    GateReport push(String repository, String branch);

    /**
     * Open a pull request for the branch and return its URL.
     *
     * <p>There is deliberately no merge. Landing the change is a person's
     * decision, and nothing here should be able to make it.
     */
    String openPullRequest(String repository, String branch, String title, String body);
}
