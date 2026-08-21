package tech.wenisch.petri.gateway;

/**
 * Result of asking the gateway to run its own push gate without pushing.
 *
 * @param passed whether the change may be pushed
 * @param output what the gate said, verbatim, for the card's history
 */
public record GateReport(boolean passed, String output) {
}
