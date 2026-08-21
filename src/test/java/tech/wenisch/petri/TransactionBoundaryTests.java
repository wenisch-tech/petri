package tech.wenisch.petri;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.gateway.*;
import tech.wenisch.petri.repository.*;
import tech.wenisch.petri.review.ReviewModel;
import tech.wenisch.petri.service.LivenessService;
import tech.wenisch.petri.service.RunnerService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that no database transaction is open while Petri talks to the agent.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}: a transactional
 * test wraps everything in one transaction of its own, so it would report a
 * transaction as active no matter how the production code is arranged, and would
 * pass just as happily against the version this replaces.
 *
 * <p>The property matters because a turn can last an hour. A transaction held
 * across that call pins a connection for its duration - wasteful at one card at
 * a time, and a pool exhaustion once cards run concurrently.
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionBoundaryTests {

    /** Records whether a transaction was active when the agent was called. */
    static class TransactionWatchingGateway implements AgentGateway {
        Boolean transactionActiveDuringStart;
        Boolean transactionActiveDuringLastMessage;

        @Override
        public String start(StartRequest request) {
            transactionActiveDuringStart = TransactionSynchronizationManager.isActualTransactionActive();
            return "ses_watch0000000001";
        }

        /** Mirrors the real adapter: a session it is not running reads as idle, not gone. */
        @Override
        public Map<String, SessionSnapshot> observe(List<String> ids) {
            Map<String, SessionSnapshot> out = new java.util.HashMap<>();
            ids.forEach(id -> out.put(id, new SessionSnapshot(id, SessionState.IDLE, null, null)));
            return out;
        }
        @Override public void abort(String sessionId) { }

        @Override
        public String lastMessage(String sessionId) {
            transactionActiveDuringLastMessage =
                    TransactionSynchronizationManager.isActualTransactionActive();
            return """
                    Done.

                    ```diff
                    diff --git a/src/Main.java b/src/Main.java
                    +int answer = 42;
                    ```
                    """;
        }
    }

    /** The slowest call in a cycle, and the one that used to run inside a transaction. */
    static class TransactionWatchingReviewer implements ReviewModel {
        Boolean transactionActiveDuringReview;

        @Override
        public String review(String system, String prompt) {
            transactionActiveDuringReview =
                    TransactionSynchronizationManager.isActualTransactionActive();
            return "VERDICT: APPROVED\n\nFine.";
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        AgentGateway watchingGateway() {
            return new TransactionWatchingGateway();
        }

        @Bean
        @Primary
        ReviewModel watchingReviewer() {
            return new TransactionWatchingReviewer();
        }
    }

    @Autowired private RunnerService runner;
    @Autowired private LivenessService liveness;
    @Autowired private AgentGateway gateway;
    @Autowired private ReviewModel reviewer;
    @Autowired private BoardRepository boards;
    @Autowired private WorkflowStateRepository states;
    @Autowired private CardRepository cards;
    @Autowired private AgentRunRepository runs;
    @Autowired private TransitionRepository history;

    private TransactionWatchingGateway watcher;
    private TransactionWatchingReviewer reviewWatcher;
    private Long reviewStateId;

    @BeforeEach
    void setUp() {
        watcher = (TransactionWatchingGateway) gateway;
        watcher.transactionActiveDuringStart = null;
        watcher.transactionActiveDuringLastMessage = null;
        reviewWatcher = (TransactionWatchingReviewer) reviewer;
        reviewWatcher.transactionActiveDuringReview = null;

        Board board = new Board();
        board.setSlug("tx-test");
        board.setName("Transaction Test");
        board.setForge(Forge.FORGEJO);
        board.setRepository("example/controlpanel");
        board.setDefaultBranch("main");
        boards.save(board);

        WorkflowState review = new WorkflowState();
        review.setBoard(board);
        review.setName("review");
        review.setPosition(1);
        review.setGate(GateType.HUMAN);
        states.save(review);
        reviewStateId = review.getId();

        WorkflowState implement = new WorkflowState();
        implement.setBoard(board);
        implement.setName("implement");
        implement.setPosition(0);
        // A verdict gate, because that is the call that made the old
        // arrangement expensive: a model asked to review, with a database
        // connection held open for as long as it took to answer.
        implement.setGate(GateType.LLM_VERDICT);
        implement.setModelAlias("coding-agent");
        implement.setNextOnPass(review);
        states.save(implement);

        Card card = new Card();
        card.setBoard(board);
        card.setState(implement);
        card.setTitle("Keep the connection free");
        card.setBranch("petri/1-keep-the-connection-free");
        cards.save(card);
    }

    @AfterEach
    void tearDown() {
        // Order matters, and so does saving: without a transaction around this
        // class, clearing the links in memory alone leaves them in the database
        // and the next test starts against a board that would not delete.
        history.deleteAll();
        runs.deleteAll();
        cards.deleteAll();
        states.findAll().forEach(state -> {
            state.setNextOnPass(null);
            state.setNextOnFail(null);
            states.save(state);
        });
        states.deleteAll();
        boards.deleteAll();
    }

    @Test
    void theAgentIsCalledWithNoTransactionOpen() {
        runner.startEligibleWork();

        assertThat(watcher.transactionActiveDuringStart)
                .as("the agent must be called outside any transaction")
                .isFalse();
    }

    @Test
    void theTranscriptAndTheReviewerAreAlsoReachedWithNoTransactionOpen() {
        runner.startEligibleWork();

        // Nothing is running, so the session reads as idle; past the startup
        // grace that means finished, which is what drives the gate.
        runs.findAll().forEach(run -> {
            run.setStartedAt(java.time.Instant.now().minus(java.time.Duration.ofHours(1)));
            runs.save(run);
        });
        liveness.observeOpenRuns();

        assertThat(watcher.transactionActiveDuringLastMessage)
                .as("reading the agent's transcript is an HTTP call")
                .isFalse();
        assertThat(reviewWatcher.transactionActiveDuringReview)
                .as("a review is a model call and can take minutes")
                .isFalse();
        // And the decision still landed. Asked by state rather than by reading
        // the card's association, which is lazy and this test holds no session.
        assertThat(cards.findByState(states.findById(reviewStateId).orElseThrow()))
                .as("the card should have moved on the reviewer's approval")
                .hasSize(1);
    }

    @Test
    void theClaimAndTheResultAreStillPersisted() {
        runner.startEligibleWork();

        // Committed by their own short transactions, not by an enclosing one.
        assertThat(runs.findAll()).singleElement().satisfies(run -> {
            assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
            assertThat(run.getSessionId()).isEqualTo("ses_watch0000000001");
            assertThat(run.getAttempt()).isEqualTo(1);
        });
        assertThat(cards.findAll()).singleElement()
                .satisfies(card -> assertThat(card.getAttempts()).isEqualTo(1));
    }
}
