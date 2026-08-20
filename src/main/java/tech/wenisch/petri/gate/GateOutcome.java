package tech.wenisch.petri.gate;

/**
 * What a gate decided, and why.
 *
 * <p>HOLD is a first-class answer, not a failure. A human gate holds until a
 * person acts, and a gate that cannot yet decide must hold rather than guess:
 * advancing on an unknown is how work reaches a pull request unchecked.
 */
public record GateOutcome(Decision decision, String reason) {

    public enum Decision { PASS, FAIL, HOLD }

    public static GateOutcome pass(String reason) {
        return new GateOutcome(Decision.PASS, reason);
    }

    public static GateOutcome fail(String reason) {
        return new GateOutcome(Decision.FAIL, reason);
    }

    public static GateOutcome hold(String reason) {
        return new GateOutcome(Decision.HOLD, reason);
    }
}
