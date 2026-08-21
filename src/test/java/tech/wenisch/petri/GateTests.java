package tech.wenisch.petri;

import org.junit.jupiter.api.Test;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.gate.*;
import tech.wenisch.petri.gateway.*;
import tech.wenisch.petri.review.ReviewException;
import tech.wenisch.petri.review.ReviewModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gates, in isolation.
 *
 * <p>One theme runs through all of it: a gate that cannot decide must
 * <em>hold</em>. Passing on an unreachable push gate, an unavailable reviewer or
 * an unparsable verdict would let unexamined work reach a pull request on the
 * strength of an error, which is the opposite of what a gate is for.
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

    /** Answers exactly what a test sets, and records nothing else. */
    static class StubGateway implements AgentGateway {
        GateReport report = new GateReport(true, "all checks pass");
        String diff = "diff --git a/x b/x";
        RuntimeException failWith;

        @Override public String start(StartRequest request) { return "ses_stub"; }
        @Override public Map<String, SessionSnapshot> observe(List<String> ids) { return Map.of(); }
        @Override public void abort(String sessionId) { }
        @Override public String lastMessage(String sessionId) { return ""; }

        @Override
        public String diff(String repository, String branch) {
            if (failWith != null) {
                throw failWith;
            }
            return diff;
        }

        @Override
        public GateReport check(String repository, String branch) {
            if (failWith != null) {
                throw failWith;
            }
            return report;
        }
    }

    // ---------------------------------------------------------------- repository

    @Test
    void repositoryGatePassesWhenThePushGatePasses() {
        StubGateway gateway = new StubGateway();
        GateOutcome outcome = new RepositoryGate(gateway).evaluate(card("petri/1-x"), succeeded("done"));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.PASS);
        assertThat(outcome.reason()).contains("all checks pass");
    }

    @Test
    void repositoryGateFailsAndKeepsTheGatesOwnWords() {
        StubGateway gateway = new StubGateway();
        gateway.report = new GateReport(false,
                "push would be refused:\n  - nothing committed against origin/main yet");

        GateOutcome outcome = new RepositoryGate(gateway).evaluate(card("petri/1-x"), succeeded("done"));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.FAIL);
        // Verbatim, because the gate's reason is what a person reads on the card
        // when working out what went wrong.
        assertThat(outcome.reason()).contains("nothing committed against origin/main");
    }

    @Test
    void anUnreachablePushGateHoldsRatherThanPasses() {
        StubGateway gateway = new StubGateway();
        gateway.failWith = new GatewayException("connection refused");

        GateOutcome outcome = new RepositoryGate(gateway).evaluate(card("petri/1-x"), succeeded("done"));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.HOLD);
        assertThat(outcome.reason()).contains("unreachable");
    }

    @Test
    void aCardWithoutABranchHasNothingToCheck() {
        GateOutcome outcome = new RepositoryGate(new StubGateway()).evaluate(card(null), succeeded("done"));

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

    private LlmVerdictGate verdictGate(StubGateway gateway, ReviewModel reviewer) {
        return new LlmVerdictGate(reviewer, gateway);
    }

    @Test
    void verdictGatePassesOnApproval() {
        GateOutcome outcome = verdictGate(new StubGateway(),
                (system, prompt) -> "VERDICT: APPROVED\n\nSmall and does what was asked.")
                .evaluate(card("petri/1-x"), succeeded("done"));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.PASS);
    }

    @Test
    void verdictGateFailsOnRejection() {
        GateOutcome outcome = verdictGate(new StubGateway(),
                (system, prompt) -> "VERDICT: REJECTED\n\nDrops the null check.")
                .evaluate(card("petri/1-x"), succeeded("done"));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(outcome.reason()).contains("Drops the null check");
    }

    @Test
    void onlyTheFirstLineDecides() {
        // A model that reasons its way round to approving at the end has not met
        // the contract it was given, and must not pass a gate on a closing
        // sentence.
        GateOutcome outcome = verdictGate(new StubGateway(),
                (system, prompt) -> "This looks risky at first glance.\n"
                        + "On reflection, VERDICT: APPROVED")
                .evaluate(card("petri/1-x"), succeeded("done"));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.HOLD);
        assertThat(outcome.reason()).contains("did not return a verdict");
    }

    @Test
    void anUnavailableReviewerHoldsRatherThanPasses() {
        GateOutcome outcome = verdictGate(new StubGateway(), (system, prompt) -> {
            throw new ReviewException("model unreachable");
        }).evaluate(card("petri/1-x"), succeeded("done"));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.HOLD);
        assertThat(outcome.reason()).contains("reviewer unavailable");
    }

    @Test
    void thereIsNothingToReviewWhenTheDiffIsEmpty() {
        StubGateway gateway = new StubGateway();
        gateway.diff = "";

        GateOutcome outcome = verdictGate(gateway, (system, prompt) -> "VERDICT: APPROVED")
                .evaluate(card("petri/1-x"), succeeded("done"));

        assertThat(outcome.decision()).isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(outcome.reason()).contains("no change to review");
    }

    @Test
    void theReviewerSeesTheTaskTheReportAndTheDiff() {
        StubGateway gateway = new StubGateway();
        gateway.diff = "diff --git a/RunnerService.java b/RunnerService.java";
        StringBuilder seen = new StringBuilder();

        verdictGate(gateway, (system, prompt) -> {
            seen.append(prompt);
            return "VERDICT: APPROVED";
        }).evaluate(card("petri/1-x"), succeeded("changed the bound to silence"));

        assertThat(seen.toString())
                .contains("Bound the turn by silence")
                .contains("changed the bound to silence")
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

        assertThat(new RepositoryGate(new StubGateway()).evaluate(card("petri/1-x"), failed).decision())
                .isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(new PlanShapeGate().evaluate(card("petri/1-x"), failed).decision())
                .isEqualTo(GateOutcome.Decision.FAIL);
        assertThat(verdictGate(new StubGateway(), (s, p) -> "VERDICT: APPROVED")
                .evaluate(card("petri/1-x"), failed).decision())
                .isEqualTo(GateOutcome.Decision.FAIL);
    }
}
