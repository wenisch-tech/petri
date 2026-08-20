package tech.wenisch.petri.gate;

import org.springframework.stereotype.Component;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.GateType;

/** A person decides. Nothing advances the card on its own. */
@Component
public class HumanGate implements Gate {

    @Override
    public GateType type() {
        return GateType.HUMAN;
    }

    @Override
    public GateOutcome evaluate(Card card, AgentRun run) {
        return GateOutcome.hold("waiting for a person");
    }
}
