package tech.wenisch.petri.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.GateType;
import tech.wenisch.petri.entity.RunStatus;

/**
 * Checks that a plan is actually a plan.
 *
 * <p>This is a shape check, not a quality judgement. It asks only whether the
 * planner named files to change and said how anyone would know the work is
 * done - the two things whose absence reliably produces an implementer that
 * wanders. Judging whether the plan is any <em>good</em> is a model's job, and
 * that is the verdict gate.
 *
 * <p>The failure this exists to prevent is concrete: a one-line task went to an
 * implementer, which spent thirteen steps searching for code that was never
 * checked out, and produced nothing. Cheap structural checks catch that before a
 * GPU-minute is spent on it.
 */
@Component
public class PlanShapeGate implements Gate {

    /** A path-looking token: at least one slash or a dotted extension. */
    private static final Pattern FILE = Pattern.compile(
            "(?<![\\w./-])(?:[\\w.-]+/)+[\\w.-]+\\.[A-Za-z0-9]{1,10}"
                    + "|(?<![\\w./-])[\\w-]+\\.[A-Za-z0-9]{1,10}(?![\\w.])");

    private static final List<String> CRITERIA_MARKERS = List.of(
            "acceptance", "criteria", "done when", "verify", "verified",
            "expected", "should ", "must ", "test");

    private static final int MINIMUM_LENGTH = 80;

    @Override
    public GateType type() {
        return GateType.PLAN_SHAPE;
    }

    @Override
    public GateOutcome evaluate(Card card, AgentRun run) {
        if (run != null && run.getStatus() != RunStatus.SUCCEEDED) {
            return GateOutcome.fail("run ended " + run.getStatus());
        }

        String plan = run == null ? null : run.getOutput();
        if (plan == null || plan.isBlank()) {
            return GateOutcome.fail("the planner produced no output");
        }

        List<String> missing = new ArrayList<>();
        if (plan.strip().length() < MINIMUM_LENGTH) {
            missing.add("a plan of any substance");
        }
        if (!FILE.matcher(plan).find()) {
            missing.add("at least one file to change");
        }
        if (!mentionsCriteria(plan)) {
            missing.add("acceptance criteria");
        }

        return missing.isEmpty()
                ? GateOutcome.pass("plan names files and acceptance criteria")
                : GateOutcome.fail("plan is missing " + String.join(", ", missing));
    }

    private boolean mentionsCriteria(String plan) {
        String lower = plan.toLowerCase();
        return CRITERIA_MARKERS.stream().anyMatch(lower::contains);
    }
}
