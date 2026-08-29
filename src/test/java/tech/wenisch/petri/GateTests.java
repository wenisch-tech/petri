package tech.wenisch.petri;

import org.junit.jupiter.api.Test;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.gate.*;
import tech.wenisch.petri.gateway.*;
import tech.wenisch.petri.review.ReviewException;
import tech.wenisch.petri.review.ReviewModel;
import tech.wenisch.petri.service.PolicySettingsService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gates, in isolation.
 *
 * <p>One theme runs through all of it: a gate that cannot decide must
 * <em>hold</em>. Passing on an unavailable reviewer or an unparsable verdict
 * would let unexamined work reach a pull request on the strength of an error,
 * which is the opposite of what a gate is for.
 */
class GateTests {

    private Card card(String branch) {
        Board board = new Board();
        board.setRepository("example/controlpanel");

        WorkflowState state = new WorkflowState();
        state.setBoard(board);
        state.setName("implement");

        Card card = new Card();
        card.setBoard(board);
        card.setState(state);
        card.setTitle("Bound the turn by silence");
        card.setBranch(branch);
        return card;
    }

    private AgentRun succeeded(String output) {
        AgentRun run = new AgentRun();
        run.setStatus(RunStatus.SUCCEEDED);
        run.setOutput(output);
        return run;
    }

    /** A run whose reported output carries a diff in the agreed fenced block. */
    private AgentRun withDiff(String patch) {
        return succeeded("""
                Changed one file.

                ```diff
                %s
                ```
                """.formatted(patch));
    }

    private static final String CLEAN_PATCH = """
            diff --git a/src/Main.java b/src/Main.java
            --- a/src/Main.java
            +++ b/src/Main.java
            +int answer = 42;
            """;

    /**
     * Overrides the two live-policy reads directly rather than going through a
     * repository, since production {@code PolicySettingsService} reads the
     * database on every call and this test has none.
     */
    private static class FixedPolicy extends PolicySettingsService {
        FixedPolicy() {
            super(null, new GatewayProperties(null, null, null, null, null, null, null, null),
                    1, "/workspaces/petri",
                    List.of(".github/**", "Dockerfile", "**/Dockerfile"), "petri/");
        }

        @Override
        public List<String> protectedPaths() {
            return List.of(".github/**", "Dockerfile", "**/Dockerfile");
        }

        @Override
        public String branchPrefix() {
            return "petri/";
        }
    }

    private RepositoryGate repositoryGate() {
        return new RepositoryGate(new ChangeInspector(), new FixedPolicy());
    }

    // ---------------------------------------------------------------- repository

    @Test
    void repositoryGatePassesACleanReportedDiff() {
        GateOutcome outcome = repositoryGate()
                .evaluate(card("petri/1-x"), withDiff(CLEAN_PATCH));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.PASS);
        assertThat(outcome.reason()).contains("changed files");
    }

    @Test
    void repositoryGateRefusesACredentialBeforeItIsPushed() {
        String leak = """
                diff --git a/app.properties b/app.properties
                --- a/app.properties
                +++ b/app.properties
                +api_key = "a-real-looking-secret"
                """;

        GateOutcome outcome = repositoryGate().evaluate(card("petri/1-x"), withDiff(leak));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(outcome.reason()).contains("possible credential");
        // And it must not repeat the secret while refusing it: this reason
        // reaches a card, the logs and a pull request body.
        assertThat(outcome.reason()).doesNotContain("a-real-looking-secret");
    }

    @Test
    void repositoryGateRefusesWhenTheAgentReportedNoDiff() {
        GateOutcome outcome = repositoryGate()
                .evaluate(card("petri/1-x"), succeeded("I had a look around."));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(outcome.reason()).contains("no diff");
    }

    @Test
    void aCardWithoutABranchHasNothingToCheck() {
        GateOutcome outcome = repositoryGate().evaluate(card(null), withDiff(CLEAN_PATCH));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(outcome.reason()).contains("no branch");
    }

    // ---------------------------------------------------------------- plan shape

    @Test
    void planShapeAcceptsAPlanThatNamesFilesAndCriteria() {
        String plan = """
                Change src/main/java/tech/wenisch/petri/service/RunnerService.java to bound
                the turn by silence rather than elapsed time, and update
                application.properties.

                Acceptance criteria: a run quiet for longer than the idle bound is
                aborted, and one still emitting is not.
                """;

        GateOutcome outcome = new PlanShapeGate().evaluate(card("petri/1-x"), succeeded(plan));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.PASS);
    }

    @Test
    void planShapeRejectsAPlanWithNoFiles() {
        String plan = "We should improve the timeout handling. Acceptance criteria: it works better.";

        GateOutcome outcome = new PlanShapeGate().evaluate(card("petri/1-x"), succeeded(plan));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(outcome.reason()).contains("file to change");
    }

    @Test
    void planShapeRejectsAPlanWithNoCriteria() {
        String plan = "Edit src/main/java/tech/wenisch/petri/service/RunnerService.java "
                + "and also src/main/resources/application.properties to change the bound.";

        GateOutcome outcome = new PlanShapeGate().evaluate(card("petri/1-x"), succeeded(plan));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(outcome.reason()).contains("acceptance criteria");
    }

    @Test
    void planShapeRejectsTheOneLineTaskThatCausedThis() {
        // The real failure: a task this thin produced an implementer that spent
        // thirteen steps hunting for code that was never checked out.
        GateOutcome outcome = new PlanShapeGate()
                .evaluate(card("petri/1-x"), succeeded("security review"));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.FAIL);
    }

    @Test
    void planShapeRejectsAPlannerThatSaidNothing() {
        GateOutcome outcome = new PlanShapeGate().evaluate(card("petri/1-x"), succeeded(""));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(outcome.reason()).contains("no output");
    }

    // -------------------------------------------------------------- llm verdict

    private LlmVerdictGate verdictGate(ReviewModel reviewer) {
        return new LlmVerdictGate(reviewer);
    }

    @Test
    void verdictGatePassesOnApproval() {
        GateOutcome outcome = verdictGate((system, prompt) -> "VERDICT: APPROVED\n\nSmall and does what was asked.")
                .evaluate(card("petri/1-x"), withDiff(CLEAN_PATCH));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.PASS);
    }

    @Test
    void verdictGateFailsOnRejection() {
        GateOutcome outcome = verdictGate((system, prompt) -> "VERDICT: REJECTED\n\nDrops the null check.")
                .evaluate(card("petri/1-x"), withDiff(CLEAN_PATCH));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(outcome.reason()).contains("Drops the null check");
    }

    @Test
    void onlyTheFirstLineDecides() {
        // A model that reasons its way round to approving at the end has not met
        // the contract it was given, and must not pass a gate on a closing
        // sentence.
        GateOutcome outcome = verdictGate((system, prompt) -> "This looks risky at first glance.\n"
                        + "On reflection, VERDICT: APPROVED")
                .evaluate(card("petri/1-x"), withDiff(CLEAN_PATCH));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.HOLD);
        assertThat(outcome.reason()).contains("did not return a verdict");
    }

    @Test
    void anUnavailableReviewerHoldsRatherThanPasses() {
        GateOutcome outcome = verdictGate((system, prompt) -> {
            throw new ReviewException("model unreachable");
        }).evaluate(card("petri/1-x"), withDiff(CLEAN_PATCH));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.HOLD);
        assertThat(outcome.reason()).contains("reviewer unavailable");
    }

    @Test
    void thereIsNothingToReviewWhenTheAgentReportedNoDiff() {
        GateOutcome outcome = verdictGate((system, prompt) -> "VERDICT: APPROVED")
                .evaluate(card("petri/1-x"), succeeded("I looked but changed nothing."));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(outcome.reason()).contains("no change to review");
    }

    @Test
    void theReviewerSeesTheTaskTheReportAndTheDiff() {
        StringBuilder seen = new StringBuilder();

        verdictGate((system, prompt) -> {
            seen.append(prompt);
            return "VERDICT: APPROVED";
        }).evaluate(card("petri/1-x"), withDiff(CLEAN_PATCH));

        assertThat(seen.toString())
                .contains("Bound the turn by silence")
                .contains("Changed one file.")
                .contains("diff --git");
    }

    // ------------------------------------------------------------------ registry

    @Test
    void anUnimplementedGateHoldsRatherThanPasses() {
        GateRegistry registry = new GateRegistry(List.of(new NoneGate()));

        GateOutcome outcome = registry.evaluate(GateType.LLM_VERDICT, card("petri/1-x"), succeeded("x"));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.HOLD);
        assertThat(outcome.reason()).contains("not implemented");
    }

    @Test
    void aFailedRunNeverReachesAnyGatesRealCheck() {
        AgentRun failed = new AgentRun();
        failed.setStatus(RunStatus.ABORTED);

        assertThat(repositoryGate().evaluate(card("petri/1-x"), failed).decision())
                .isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(new PlanShapeGate().evaluate(card("petri/1-x"), failed).decision())
                .isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(verdictGate((s, p) -> "VERDICT: APPROVED")
                .evaluate(card("petri/1-x"), failed).decision())
                .isEqualTo(GateOutcome.Decision.FAIL);
    }
}
