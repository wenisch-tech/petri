package tech.wenisch.petri.config;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.repository.*;

/**
 * Seeds one example pipeline so the board has something to show.
 *
 * <p>Off by default and opt-in with {@code petri.seed-demo=true}. Inventing a
 * repository and cards in someone's real instance would be worse than an empty
 * board, and an empty board is only confusing without an explanation - which the
 * boards page gives.
 */
@Configuration
@ConditionalOnProperty(name = "petri.seed-demo", havingValue = "true")
public class DemoDataInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(DemoDataInitializer.class);

    @Bean
    ApplicationRunner seedDemoBoard(BoardRepository boards,
                                    WorkflowStateRepository states,
                                    CardRepository cards,
                                    AgentRunRepository runs,
                                    TransitionRepository transitions) {
        return args -> seed(boards, states, cards, runs, transitions);
    }

    @Transactional
    void seed(BoardRepository boards,
              WorkflowStateRepository states,
              CardRepository cards,
              AgentRunRepository runs,
              TransitionRepository transitions) {

        if (boards.findBySlug("demo").isPresent()) {
            return;
        }
        LOG.info("Seeding demo board (petri.seed-demo=true)");

        Board board = new Board();
        board.setSlug("demo");
        board.setName("Demo");
        board.setForge(Forge.FORGEJO);
        board.setRepository("example/controlpanel");
        board.setDefaultBranch("main");
        boards.save(board);

        WorkflowState planner = state(states, board, "planner", 0, GateType.PLAN_SHAPE, "chatgpt");
        WorkflowState implement = state(states, board, "implement", 1, GateType.REPOSITORY, "coding-agent");
        WorkflowState review = state(states, board, "review", 2, GateType.LLM_VERDICT, "chatgpt");
        WorkflowState human = state(states, board, "human", 3, GateType.HUMAN, null);
        WorkflowState done = state(states, board, "done", 4, GateType.NONE, null);
        done.setTerminal(true);

        planner.setNextOnPass(implement);
        planner.setNextOnFail(planner);
        implement.setNextOnPass(review);
        implement.setNextOnFail(implement);
        review.setNextOnPass(human);
        review.setNextOnFail(implement);
        human.setNextOnPass(done);
        states.saveAll(List.of(planner, implement, review, human, done));

        Card queued = card(cards, board, planner, "Add rate limiting to the public API", null);

        Card running = card(cards, board, implement,
                "Replace the wall-clock turn timeout with an idle timeout",
                "petri/2-idle-timeout");
        running.setAttempts(1);
        cards.save(running);

        AgentRun run = new AgentRun();
        run.setCard(running);
        run.setState(implement);
        run.setAttempt(1);
        run.setSessionId("ses_demo0000000001");
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now().minusSeconds(1_800));
        run.setLastEventAt(Instant.now().minusSeconds(140));
        runs.save(run);

        Transition started = new Transition();
        started.setCard(running);
        started.setFromState(planner);
        started.setToState(implement);
        started.setActor("chatgpt");
        started.setVerdict("plan names files and acceptance criteria");
        transitions.save(started);

        Card waiting = card(cards, board, human,
                "Scope sessions to the branch rather than the checkout",
                "petri/3-branch-scoped-sessions");
        waiting.setPullRequestUrl("https://example.invalid/example/controlpanel/pulls/12");
        cards.save(waiting);

        LOG.info("Demo board seeded with {} cards", cards.findByBoardOrderByIdAsc(board).size());
        if (queued.getId() == null) {
            LOG.warn("Demo card was not persisted");
        }
    }

    private WorkflowState state(WorkflowStateRepository states, Board board, String name,
                                int position, GateType gate, String model) {
        WorkflowState state = new WorkflowState();
        state.setBoard(board);
        state.setName(name);
        state.setPosition(position);
        state.setGate(gate);
        state.setModelAlias(model);
        return states.save(state);
    }

    private Card card(CardRepository cards, Board board, WorkflowState state,
                      String title, String branch) {
        Card card = new Card();
        card.setBoard(board);
        card.setState(state);
        card.setTitle(title);
        card.setBranch(branch);
        return cards.save(card);
    }
}
