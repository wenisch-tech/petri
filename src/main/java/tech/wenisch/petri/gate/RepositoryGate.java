package tech.wenisch.petri.gate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.GateType;
import tech.wenisch.petri.entity.RunStatus;
import tech.wenisch.petri.forge.BranchChange;

/**
 * Inspects the change <em>before</em> it is pushed.
 *
 * <p>The agent commits locally and reports its diff; this reads that diff and
 * decides whether a push may happen at all. Running the check first is what
 * keeps a credential off the remote - once a branch is pushed, a secret is in
 * the forge's history whatever anyone decides afterwards.
 *
 * <p>The diff is agent-reported, so this cannot be the only check. The same
 * inspection runs again on what actually landed, before a pull request is
 * opened, which catches an agent whose report did not match its commits.
 */
@Component
public class RepositoryGate implements Gate {

    private static final Logger LOG = LoggerFactory.getLogger(RepositoryGate.class);

    private final ChangeInspector inspector;

    public RepositoryGate(ChangeInspector inspector) {
        this.inspector = inspector;
    }

    @Override
    public GateType type() {
        return GateType.REPOSITORY;
    }

    @Override
    public GateOutcome evaluate(Card card, AgentRun run) {
        if (run == null) {
            return GateOutcome.fail("no run to inspect");
        }
        if (run.getStatus() != RunStatus.SUCCEEDED) {
            return GateOutcome.fail("run ended " + run.getStatus());
        }

        String patch = ReportedDiff.from(run.getOutput());
        if (patch.isBlank()) {
            // Not a pass. An agent that reported no diff either changed nothing
            // or did not follow the contract, and neither is something to push.
            return GateOutcome.fail("the agent reported no diff");
        }

        BranchChange reported = BranchChange.fromReportedPatch(patch);
        var problems = inspector.inspect(reported, card.getBranch(),
                card.getBoard().getDefaultBranch());

        if (problems.isEmpty()) {
            LOG.debug("Card {} passed inspection over {} files",
                    card.getId(), reported.files().size());
            return GateOutcome.pass("inspected " + reported.files().size()
                    + " changed files, nothing objectionable");
        }
        return GateOutcome.fail(String.join("; ", problems));
    }
}
