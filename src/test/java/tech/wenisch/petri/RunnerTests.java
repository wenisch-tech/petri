package tech.wenisch.petri;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.gateway.*;
import tech.wenisch.petri.repository.*;
import tech.wenisch.petri.service.LivenessService;
import tech.wenisch.petri.service.RunnerService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the runner and the liveness poller against a fake gateway.
 *
 * <p>The interesting assertions are about time. A run that has been going for
 * fifty minutes but spoke two minutes ago must survive; one that has said
 * nothing past the idle bound must not. Getting that backwards - bounding by
 * elapsed time - killed working runs and reported them as successes.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RunnerTests {

    /** Records what it was asked to do, and answers whatever the test sets. */
    static class FakeGateway implements AgentGateway {
        final List<StartRequest> started = new ArrayList<>();
        final List<String> aborted = new ArrayList<>();
        final Map<String, SessionSnapshot> snapshots = new HashMap<>();
        String nextSessionId = "ses_fake0000000001";
        boolean failNextStart = false;

        @Override
        public String start(StartRequest request) {
            if (failNextStart) {
                throw new GatewayException("gateway unavailable");
            }
            started.add(request);
            return nextSessionId;
        }

        @Override
        public Map<String, SessionSnapshot> observe(List<String> sessionIds) {
            Map<String, SessionSnapshot> out = new HashMap<>();
            sessionIds.forEach(id -> out.put(id,
                    snapshots.getOrDefault(id, SessionSnapshot.unknown(id))));
            return out;
        }

        @Override
        public void abort(String sessionId) {
            aborted.add(sessionId);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        AgentGateway fakeGateway() {
            return new FakeGateway();
        }
    }

    @Autowired private RunnerService runner;
    @Autowired private LivenessService liveness;
    @Autowired private AgentGateway gateway;
    @Autowired private BoardRepository boards;
    @Autowired private WorkflowStateRepository states;
    @Autowired private CardRepository cards;
    @Autowired private AgentRunRepository runs;
    @Autowired private TransitionRepository transitions;

    private FakeGateway fake;
    private WorkflowState implement;
    private WorkflowState review;
    private Card card;

    @BeforeEach
    void setUp() {
        fake = (FakeGateway) gateway;
        fake.started.clear();
        fake.aborted.clear();
        fake.snapshots.clear();
        fake.failNextStart = false;

        Board board = new Board();
        board.setSlug("runner-test");
        board.setName("Runner Test");
        board.setForge(Forge.FORGEJO);
        board.setRepository("example/controlpanel");
        board.setDefaultBranch("main");
        boards.save(board);

        implement = new WorkflowState();
        implement.setBoard(board);
        implement.setName("implement");
        implement.setPosition(0);
        implement.setGate(GateType.NONE);
        implement.setModelAlias("coding-agent");
        implement.setMaxAttempts(2);
        states.save(implement);

        review = new WorkflowState();
        review.setBoard(board);
        review.setName("review");
        review.setPosition(1);
        review.setGate(GateType.HUMAN);
        states.save(review);

        implement.setNextOnPass(review);
        implement.setNextOnFail(implement);
        states.save(implement);

        card = new Card();
        card.setBoard(board);
        card.setState(implement);
        card.setTitle("Bound the turn by silence");
        cards.save(card);
    }

    @Test
    void startsWorkForCardsInAnAutomatedState() {
        runner.startEligibleWork();

        assertThat(fake.started).hasSize(1);
        assertThat(fake.started.getFirst().repository()).isEqualTo("example/controlpanel");
        // A branch is derived when the card has none: the branch owns the
        // session, so it has to exist before any work starts.
        assertThat(fake.started.getFirst().branch()).startsWith("petri/");

        AgentRun run = runs.findFirstByCardOrderByIdDesc(card).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getSessionId()).isEqualTo("ses_fake0000000001");
        assertThat(run.getLastEventAt()).isNotNull();
    }

    @Test
    void doesNotStartASecondRunWhileOneIsOpen() {
        runner.startEligibleWork();
        runner.startEligibleWork();

        assertThat(fake.started).hasSize(1);
    }

    @Test
    void stopsStartingOnceAttemptsAreExhausted() {
        // maxAttempts is 2, and each start consumes one.
        runner.startEligibleWork();
        finishLatestAs(RunStatus.FAILED);
        runner.startEligibleWork();
        finishLatestAs(RunStatus.FAILED);
        runner.startEligibleWork();

        assertThat(fake.started).hasSize(2);
    }

    @Test
    void aFailureToStartIsRecordedRatherThanThrown() {
        fake.failNextStart = true;
        runner.startEligibleWork();

        AgentRun run = runs.findFirstByCardOrderByIdDesc(card).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getSummary()).contains("gateway unavailable");
    }

    @Test
    void aBusyRunThatSpokeRecentlySurvives() {
        runner.startEligibleWork();
        AgentRun run = runs.findFirstByCardOrderByIdDesc(card).orElseThrow();

        // Fifty minutes old, two minutes quiet: healthy. Bounding by elapsed
        // time would kill this, which is precisely the bug being guarded.
        run.setStartedAt(Instant.now().minus(Duration.ofMinutes(50)));
        runs.save(run);
        fake.snapshots.put(run.getSessionId(), new SessionSnapshot(
                run.getSessionId(), SessionState.BUSY,
                Instant.now().minus(Duration.ofMinutes(2)), null));

        liveness.observeOpenRuns();

        assertThat(runs.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.RUNNING);
        assertThat(fake.aborted).isEmpty();
    }

    @Test
    void aSilentRunIsAborted() {
        runner.startEligibleWork();
        AgentRun run = runs.findFirstByCardOrderByIdDesc(card).orElseThrow();

        // Default idle bound is fifteen minutes.
        fake.snapshots.put(run.getSessionId(), new SessionSnapshot(
                run.getSessionId(), SessionState.BUSY,
                Instant.now().minus(Duration.ofMinutes(40)), null));

        liveness.observeOpenRuns();

        AgentRun after = runs.findById(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RunStatus.ABORTED);
        assertThat(after.getSummary()).contains("produced no output");
        assertThat(fake.aborted).containsExactly(run.getSessionId());
    }

    @Test
    void anIdleSessionCompletesAndTheGateMovesTheCard() {
        runner.startEligibleWork();
        AgentRun run = runs.findFirstByCardOrderByIdDesc(card).orElseThrow();

        fake.snapshots.put(run.getSessionId(), new SessionSnapshot(
                run.getSessionId(), SessionState.IDLE, Instant.now(), null));

        liveness.observeOpenRuns();

        assertThat(runs.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.SUCCEEDED);
        assertThat(cards.findById(card.getId()).orElseThrow().getState().getName())
                .isEqualTo("review");
        // Arriving somewhere new resets the budget, so a retry loop in one state
        // cannot consume another state's attempts.
        assertThat(cards.findById(card.getId()).orElseThrow().getAttempts()).isZero();
        assertThat(transitions.findByCardOrderByIdAsc(card)).hasSize(1);
    }

    @Test
    void aHumanGateHoldsTheCardWhereItIs() {
        // Move the card into the human-gated state, then let a run there end.
        card.setState(review);
        cards.save(card);

        AgentRun run = new AgentRun();
        run.setCard(card);
        run.setState(review);
        run.setAttempt(1);
        run.setSessionId("ses_human000000001");
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run.setLastEventAt(Instant.now());
        runs.save(run);

        fake.snapshots.put("ses_human000000001", new SessionSnapshot(
                "ses_human000000001", SessionState.IDLE, Instant.now(), null));

        liveness.observeOpenRuns();

        assertThat(cards.findById(card.getId()).orElseThrow().getState().getName())
                .isEqualTo("review");
        assertThat(transitions.findByCardOrderByIdAsc(card)).isEmpty();
    }

    @Test
    void aRetryingSessionKeepsItsReasonVisible() {
        runner.startEligibleWork();
        AgentRun run = runs.findFirstByCardOrderByIdDesc(card).orElseThrow();

        fake.snapshots.put(run.getSessionId(), new SessionSnapshot(
                run.getSessionId(), SessionState.RETRY, Instant.now(), "provider timed out"));

        liveness.observeOpenRuns();

        AgentRun after = runs.findById(run.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(after.getSummary()).isEqualTo("provider timed out");
    }

    private void finishLatestAs(RunStatus status) {
        AgentRun run = runs.findFirstByCardOrderByIdDesc(card).orElseThrow();
        run.setStatus(status);
        run.setFinishedAt(Instant.now());
        runs.save(run);
    }
}
