package tech.wenisch.petri;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.repository.*;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the card page with <strong>no surrounding transaction</strong>.
 *
 * <p>This is deliberately not {@code @Transactional}. The card page once threw
 * {@code LazyInitializationException} in production while the transactional test
 * for the same page passed, because the test held the persistence session open
 * for the whole method and the real request does not: {@code open-in-view} is
 * false, so the session is closed by the time Thymeleaf renders.
 *
 * <p>Committing the fixture and cleaning it up afterwards costs a little
 * bookkeeping and buys the only conditions under which that class of bug shows.
 */
@SpringBootTest
@ActiveProfiles("test")
class CardPageRenderTests {

    @Autowired private WebApplicationContext context;
    @Autowired private BoardRepository boards;
    @Autowired private WorkflowStateRepository states;
    @Autowired private CardRepository cards;
    @Autowired private AgentRunRepository runs;
    @Autowired private TransitionRepository transitions;

    private MockMvc mockMvc;
    private Long cardId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        Board board = new Board();
        board.setSlug("render-test");
        board.setName("Render Test");
        board.setForge(Forge.FORGEJO);
        board.setRepository("example/controlpanel");
        board.setDefaultBranch("main");
        boards.save(board);

        WorkflowState planner = new WorkflowState();
        planner.setBoard(board);
        planner.setName("planner");
        planner.setPosition(0);
        planner.setGate(GateType.PLAN_SHAPE);
        planner.setModelAlias("chatgpt");
        states.save(planner);

        WorkflowState implement = new WorkflowState();
        implement.setBoard(board);
        implement.setName("implement");
        implement.setPosition(1);
        implement.setGate(GateType.REPOSITORY);
        implement.setModelAlias("coding-agent");
        states.save(implement);

        Card card = new Card();
        card.setBoard(board);
        card.setState(implement);
        card.setTitle("Render without an open session");
        card.setBranch("petri/11-render");
        cards.save(card);
        cardId = card.getId();

        AgentRun run = new AgentRun();
        run.setCard(card);
        run.setState(implement);
        run.setAttempt(1);
        run.setSessionId("ses_render000000001");
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now().minusSeconds(600));
        run.setLastEventAt(Instant.now().minusSeconds(30));
        runs.save(run);

        // A transition with a from-state is what actually broke: reaching
        // fromState.name from the template touched a lazy proxy.
        Transition moved = new Transition();
        moved.setCard(card);
        moved.setFromState(planner);
        moved.setToState(implement);
        moved.setActor("chatgpt");
        moved.setVerdict("plan accepted");
        transitions.save(moved);
    }

    @AfterEach
    void tearDown() {
        transitions.deleteAll();
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
    void cardPageRendersOutsideATransaction() throws Exception {
        mockMvc.perform(get("/cards/" + cardId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Render without an open session")))
                .andExpect(content().string(containsString("planner")))
                .andExpect(content().string(containsString("implement")))
                .andExpect(content().string(containsString("plan accepted")))
                .andExpect(content().string(containsString("ses_render000000001")));
    }

    @Test
    void boardPageRendersOutsideATransaction() throws Exception {
        mockMvc.perform(get("/boards/render-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Render Test")))
                .andExpect(content().string(containsString("coding-agent")));
    }
}
