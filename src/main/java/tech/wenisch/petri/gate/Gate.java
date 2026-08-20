package tech.wenisch.petri.gate;

import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.GateType;

/** Decides whether a card may leave the state it is in. */
public interface Gate {

    GateType type();

    GateOutcome evaluate(Card card, AgentRun run);
}
