package tech.wenisch.petri.entity;

/**
 * What decides whether a card may leave a state.
 *
 * <p>The gate is where correctness lives. Without an explicit place for it, the
 * checks end up as scattered conditionals in whatever drives the agent.
 */
public enum GateType {
    /** Advance as soon as the run finishes. */
    NONE,
    /** The agent gateway's own push gate: secrets, protected paths, tests, rebase. */
    REPOSITORY,
    /** An independent model must return an explicit approval verdict. */
    LLM_VERDICT,
    /** The produced plan must name files and acceptance criteria. */
    PLAN_SHAPE,
    /** A person decides. Nothing advances the card automatically. */
    HUMAN
}
