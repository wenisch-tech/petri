package tech.wenisch.petri.gate;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.GateType;

/**
 * Finds the gate for a state.
 *
 * <p>A gate type with no implementation holds rather than passes. Passing an
 * unimplemented check would let work through unexamined, which is exactly the
 * failure the gates exist to prevent; holding is visible and safe.
 */
@Component
public class GateRegistry {

    private final Map<GateType, Gate> gates = new EnumMap<>(GateType.class);

    public GateRegistry(List<Gate> implementations) {
        implementations.forEach(gate -> gates.put(gate.type(), gate));
    }

    public GateOutcome evaluate(GateType type, Card card, AgentRun run) {
        Gate gate = gates.get(type);
        if (gate == null) {
            return GateOutcome.hold(type + " gate is not implemented yet");
        }
        return gate.evaluate(card, run);
    }
}
