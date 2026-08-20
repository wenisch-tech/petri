package tech.wenisch.petri;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.repository.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds the pipeline from the README - planner, implement, review, human - and
 * walks a card through it, so the schema is exercised as a state machine rather
 * than as five tables that happen to persist.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkflowPersistenceTests {

    @Autowired private BoardRepository boards;
    @Autowired private WorkflowStateRepository states;
    @Autowired private CardRepository cards;
    @Autowired private AgentRunRepository runs;
    @Autowired private TransitionRepository transitions;

    private Board board(String slug) {
        Board board = new Board();
        board.setSlug(slug);
        board.setName("Controlpanel");
        board.setForge(Forge.FORGEJO);
        board.setRepository("example/controlpanel");
        board.setDefaultBranch("main");
        return boards.save(board);
    }

    private WorkflowState state(Board board, String name, int position, GateType gate, String model) {
        WorkflowState state = new WorkflowState();
        state.setBoard(board);
        state.setName(name);
        state.setPosition(position);
        state.setGate(gate);
        state.setModelAlias(model);
        return states.save(state);
    }

    @Test
    void aPipelineIsConfigurationRatherThanCode() {
        Board board = board("pipeline");

        WorkflowState planner = state(board, "planner", 0, GateType.PLAN_SHAPE, "chatgpt");
        WorkflowState implement = state(board, "implement", 1, GateType.REPOSITORY, "coding-agent");
        WorkflowState review = state(board, "review", 2, GateType.LLM_VERDICT, "chatgpt");
        WorkflowState human = state(board, "human", 3, GateType.HUMAN, null);
        WorkflowState done = state(board, "done", 4, GateType.NONE, null);
        done.setTerminal(true);

        planner.setNextOnPass(implement);
        planner.setNextOnFail(planner);
        implement.setNextOnPass(review);
        implement.setNextOnFail(implement);
        review.setNextOnPass(human);
        review.setNextOnFail(implement);
        human.setNextOnPass(done);
        states.saveAll(List.of(planner, implement, review, human, done));
        states.flush();

        List<WorkflowState> ordered = states.findByBoardOrderByPositionAsc(board);
        assertThat(ordered).extracting(WorkflowState::getName)
                .containsExactly("planner", "implement", "review", "human", "done");

        // A state drives itself only when a model is bound and it is not terminal.
        assertThat(ordered).filteredOn(WorkflowState::isAutomated)
                .extracting(WorkflowState::getName)
                .containsExactly("planner", "implement", "review");

        // Failure loops back rather than dead-ending, which is what lets a card
        // be retried in place and makes "stuck in implement" distinguishable
        // from "never left planner".
        assertThat(review.getNextOnFail().getName()).isEqualTo("implement");
        assertThat(done.getNextOnPass()).isNull();
    }

    @Test
    void aCardCarriesItsHistoryAcrossStates() {
        Board board = board("history");
        WorkflowState implement = state(board, "implement", 0, GateType.REPOSITORY, "coding-agent");
        WorkflowState review = state(board, "review", 1, GateType.LLM_VERDICT, "chatgpt");
        implement.setNextOnPass(review);
        states.saveAll(List.of(implement, review));

        Card card = new Card();
        card.setBoard(board);
        card.setState(implement);
        card.setTitle("Tighten session handling");
        card.setBranch("petri/1-tighten-session-handling");
        card = cards.save(card);

        AgentRun run = new AgentRun();
        run.setCard(card);
        run.setState(implement);
        run.setAttempt(1);
        run.setSessionId("ses_abc123");
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now().minusSeconds(300));
        run.setLastEventAt(Instant.now().minusSeconds(20));
        run = runs.save(run);

        Transition moved = new Transition();
        moved.setCard(card);
        moved.setFromState(implement);
        moved.setToState(review);
        moved.setRun(run);
        moved.setActor("coding-agent");
        moved.setVerdict("repository gate passed");
        transitions.save(moved);

        card.setState(review);
        card.setAttempts(0);
        cards.saveAndFlush(card);

        assertThat(cards.countByState(review)).isEqualTo(1);
        assertThat(cards.countByState(implement)).isZero();

        List<Transition> history = transitions.findByCardOrderByIdAsc(card);
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getActor()).isEqualTo("coding-agent");
        assertThat(history.getFirst().getFromState().getName()).isEqualTo("implement");
        assertThat(history.getFirst().getCreatedAt()).isNotNull();

        assertThat(runs.findFirstByCardOrderByIdDesc(card))
                .get().extracting(AgentRun::getSessionId).isEqualTo("ses_abc123");
    }

    @Test
    void silenceIsMeasuredFromTheLastEventNotTheStart() {
        Instant now = Instant.now();
        AgentRun run = new AgentRun();
        run.setStartedAt(now.minus(Duration.ofMinutes(40)));
        run.setLastEventAt(now.minus(Duration.ofMinutes(2)));

        // Forty minutes in and perfectly healthy: elapsed time says nothing about
        // whether an agent is working, which is why a wall-clock bound kills runs
        // that are still producing output.
        assertThat(run.silenceFor(now)).isCloseTo(Duration.ofMinutes(2), Duration.ofSeconds(5));

        AgentRun neverReported = new AgentRun();
        neverReported.setStartedAt(now.minus(Duration.ofMinutes(20)));
        assertThat(neverReported.silenceFor(now))
                .isCloseTo(Duration.ofMinutes(20), Duration.ofSeconds(5));
    }
}
