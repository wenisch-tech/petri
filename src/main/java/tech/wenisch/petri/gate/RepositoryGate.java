package tech.wenisch.petri.gate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.GateType;
import tech.wenisch.petri.entity.RunStatus;
import tech.wenisch.petri.gateway.AgentGateway;
import tech.wenisch.petri.gateway.GateReport;
import tech.wenisch.petri.gateway.GatewayException;

/**
 * Asks the gateway to run its own push gate.
 *
 * <p>Petri reimplements none of it. Secret scanning over every commit in the
 * range rather than a squashed diff, protected paths, test detection, and
 * re-running the whole gate after a rebase all live on the other side of this
 * call, where they were hardened by real failures. Duplicating them here would
 * mean a second copy to keep in step, and the copy that drifts is the one that
 * lets something through.
 */
@Component
public class RepositoryGate implements Gate {

    private static final Logger LOG = LoggerFactory.getLogger(RepositoryGate.class);
    private static final int MAX_REASON = 2000;

    private final AgentGateway gateway;

    public RepositoryGate(AgentGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public GateType type() {
        return GateType.REPOSITORY;
    }

    @Override
    public GateOutcome evaluate(Card card, AgentRun run) {
        if (run != null && run.getStatus() != RunStatus.SUCCEEDED) {
            return GateOutcome.fail("run ended " + run.getStatus());
        }
        if (card.getBranch() == null || card.getBranch().isBlank()) {
            return GateOutcome.fail("card has no branch, so there is nothing to check");
        }

        try {
            GateReport report = gateway.check(card.getBoard().getRepository(), card.getBranch());
            String reason = trim(report.output());
            return report.passed() ? GateOutcome.pass(reason) : GateOutcome.fail(reason);

        } catch (GatewayException ex) {
            // Unreachable is not the same as clean. Holding keeps the card where
            // it is and visible; passing would let an unchecked change onward on
            // the strength of a network error.
            LOG.warn("Repository gate could not reach the gateway for card {}: {}",
                    card.getId(), ex.getMessage());
            return GateOutcome.hold("push gate unreachable: " + ex.getMessage());
        }
    }

    private String trim(String output) {
        if (output == null || output.isBlank()) {
            return "push gate returned no output";
        }
        String trimmed = output.strip();
        return trimmed.length() <= MAX_REASON ? trimmed
                : trimmed.substring(trimmed.length() - MAX_REASON);
    }
}
