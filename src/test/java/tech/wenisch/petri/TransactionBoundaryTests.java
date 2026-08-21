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

        @Override
        public String start(StartRequest request) {
            transactionActiveDuringStart = TransactionSynchronizationManager.isActualTransactionActive();
            return "ses_watch0000000001";
        }

        @Override public Map<String, SessionSnapshot> observe(List<String> ids) { return Map.of(); }
        @Override public void abort(String sessionId) { }
        @Override public String lastMessage(String sessionId) { return ""; }
        @Override public String diff(String repository, String branch) { return ""; }
        @Override public GateReport check(String r, String b) { return new GateReport(true, "ok"); }
        @Override public GateReport push(String r, String b) { return new GateReport(true, "ok"); }
        @Override public String openPullRequest(String r, String b, String t, String body) {
            return "https://example.invalid/pulls/1";
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        AgentGateway watchingGateway() {
            return new TransactionWatchingGateway();
        }
    }

    @Autowired private RunnerService runner;
    @Autowired private AgentGateway gateway;
    @Autowired private BoardRepository boards;
    @Autowired private WorkflowStateRepository states;
    @Autowired private CardRepository cards;
    @Autowired private AgentRunRepository runs;

    private TransactionWatchingGateway watcher;

    @BeforeEach
    void setUp() {
        watcher = (TransactionWatchingGateway) gateway;
        watcher.transactionActiveDuringStart = null;

        Board board = new Board();
        board.setSlug("tx-test");
        board.setName("Transaction Test");
        board.setForge(Forge.FORGEJO);
        board.setRepository("example/controlpanel");
        board.setDefaultBranch("main");
        boards.save(board);

        WorkflowState implement = new WorkflowState();
        implement.setBoard(board);
        implement.setName("implement");
        implement.setPosition(0);
        implement.setGate(GateType.NONE);
        implement.setModelAlias("coding-agent");
        states.save(implement);

        Card card = new Card();
        card.setBoard(board);
        card.setState(implement);
        card.setTitle("Keep the connection free");
        cards.save(card);
    }

    @AfterEach
    void tearDown() {
        runs.deleteAll();
        cards.deleteAll();
        states.findAll().forEach(state -> {
            state.setNextOnPass(null);
            state.setNextOnFail(null);
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
