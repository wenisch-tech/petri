package tech.wenisch.petri.gate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.GateType;
import tech.wenisch.petri.entity.RunStatus;
import tech.wenisch.petri.gateway.AgentGateway;
import tech.wenisch.petri.gateway.GatewayException;
import tech.wenisch.petri.review.ReviewException;
import tech.wenisch.petri.review.ReviewModel;

/**
 * An independent model reads the diff and returns a verdict.
 *
 * <p>Independent in two senses that both matter: a different process from the
 * one that wrote the change, and - by configuration - a different model. A
 * reviewer marking its own work is not a review.
 *
 * <p>It runs <em>before</em> anything is pushed, on the diff the gateway is
 * holding, so a rejected change never reaches the forge at all.
 */
@Component
public class LlmVerdictGate implements Gate {

    private static final Logger LOG = LoggerFactory.getLogger(LlmVerdictGate.class);

    private static final String APPROVED = "VERDICT: APPROVED";
    private static final String REJECTED = "VERDICT: REJECTED";
    private static final int MAX_DIFF = 60_000;
    private static final int MAX_REASON = 3000;

    private static final String SYSTEM = """
            You are reviewing a change produced by another AI agent, before it is
            pushed to a repository.

            Judge only whether the change does what the task asked, is safe, and
            does not break existing behaviour. You are not being asked to improve
            it or to comment on style.

            Your first line must be exactly one of:
            VERDICT: APPROVED
            VERDICT: REJECTED

            Then give your reasoning in a few sentences. If anything material is
            unclear, reject and say what is missing.
            """;

    private final ReviewModel reviewer;
    private final AgentGateway gateway;

    public LlmVerdictGate(ReviewModel reviewer, AgentGateway gateway) {
        this.reviewer = reviewer;
        this.gateway = gateway;
    }

    @Override
    public GateType type() {
        return GateType.LLM_VERDICT;
    }

    @Override
    public GateOutcome evaluate(Card card, AgentRun run) {
        if (run != null && run.getStatus() != RunStatus.SUCCEEDED) {
            return GateOutcome.fail("run ended " + run.getStatus());
        }

        String diff;
        try {
            diff = gateway.diff(card.getBoard().getRepository(), card.getBranch());
        } catch (GatewayException ex) {
            return GateOutcome.hold("could not read the diff: " + ex.getMessage());
        }

        if (diff == null || diff.isBlank()) {
            return GateOutcome.fail("there is no change to review");
        }

        String verdict;
        try {
            verdict = reviewer.review(SYSTEM, prompt(card, run, diff));
        } catch (ReviewException ex) {
            // An unavailable reviewer is not an approval. Holding leaves the card
            // where it is, visibly waiting, instead of letting an unreviewed
            // change through because a model was briefly unreachable.
            LOG.warn("Verdict gate could not review card {}: {}", card.getId(), ex.getMessage());
            return GateOutcome.hold("reviewer unavailable: " + ex.getMessage());
        }

        return interpret(verdict);
    }

    /**
     * Read the verdict from the first line only.
     *
     * <p>Deliberate: a model that argues its way round to "approved" in a closing
     * paragraph should not pass a gate whose contract was one line at the top.
     */
    private GateOutcome interpret(String verdict) {
        String text = verdict == null ? "" : verdict.strip();
        String head = text.lines().findFirst().orElse("").strip().toUpperCase();
        String reason = text.length() <= MAX_REASON ? text : text.substring(0, MAX_REASON);

        if (head.startsWith(APPROVED)) {
            return GateOutcome.pass(reason);
        }
        if (head.startsWith(REJECTED)) {
            return GateOutcome.fail(reason);
        }
        // Neither verdict is not a pass. An unparsable answer means the reviewer
        // did not follow its contract, and guessing which way it leaned would
        // defeat the point of asking.
        return GateOutcome.hold("reviewer did not return a verdict: " + reason);
    }

    private String prompt(Card card, AgentRun run, String diff) {
        String trimmedDiff = diff.length() <= MAX_DIFF ? diff : diff.substring(0, MAX_DIFF)
                + "\n... diff truncated ...";

        return """
                # Task
                %s

                %s

                # What the agent reported
                %s

                # Diff
                ```diff
                %s
                ```
                """.formatted(
                card.getTitle(),
                card.getDescription() == null ? "" : card.getDescription(),
                run == null || run.getOutput() == null ? "(nothing reported)" : run.getOutput(),
                trimmedDiff);
    }
}
