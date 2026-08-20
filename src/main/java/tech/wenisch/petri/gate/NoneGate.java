package tech.wenisch.petri.gate;

import org.springframework.stereotype.Component;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.GateType;
import tech.wenisch.petri.entity.RunStatus;

/** No check beyond the run itself having succeeded. */
@Component
public class NoneGate implements Gate {

    @Override
    public GateType type() {
        return GateType.NONE;
    }

    @Override
    public GateOutcome evaluate(Card card, AgentRun run) {
        if (run == null) {
            return GateOutcome.pass("no run required");
        }
        return run.getStatus() == RunStatus.SUCCEEDED
                ? GateOutcome.pass("run completed")
                : GateOutcome.fail("run ended " + run.getStatus());
    }
}
