package tech.wenisch.petri;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.repository.*;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Renders the board and a card, so the templates are exercised, not just the queries. */
@SpringBootTest
@ActiveProfiles("test")
// Transactional so each method rolls back. Without it the seeded board leaks
// into other test classes, where a single board makes "/" redirect instead
// of listing, and the per-method guard below leaves cardId null.
@Transactional
class BoardViewTests {

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
        board.setSlug("view-test");
        board.setName("View Test");
        board.setForge(Forge.FORGEJO);
        board.setRepository("example/controlpanel");
        board.setDefaultBranch("main");
        boards.save(board);

        WorkflowState implement = new WorkflowState();
        implement.setBoard(board);
        implement.setName("implement");
        implement.setPosition(0);
        implement.setGate(GateType.REPOSITORY);
        implement.setModelAlias("coding-agent");
        states.save(implement);

        WorkflowState review = new WorkflowState();
        review.setBoard(board);
        review.setName("review");
        review.setPosition(1);
        review.setGate(GateType.LLM_VERDICT);
        states.save(review);

        Card card = new Card();
        card.setBoard(board);
        card.setState(implement);
        card.setTitle("Bound a turn by silence");
        card.setBranch("petri/9-bound-by-silence");
        card.setAttempts(2);
        cards.save(card);
        cardId = card.getId();

        AgentRun run = new AgentRun();
        run.setCard(card);
        run.setState(implement);
        run.setAttempt(2);
        run.setSessionId("ses_view0000000001");
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(Instant.now().minusSeconds(3_000));
        run.setLastEventAt(Instant.now().minusSeconds(120));
        runs.save(run);

        Transition moved = new Transition();
        moved.setCard(card);
        moved.setToState(implement);
        moved.setActor("coding-agent");
        moved.setVerdict("queued");
        transitions.save(moved);
    }

    @Test
    void boardRendersStatesAsColumns() throws Exception {
        mockMvc.perform(get("/boards/view-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("implement")))
                .andExpect(content().string(containsString("review")))
                .andExpect(content().string(containsString("coding-agent")))
                // A state with no model is not driven by anything, and must not
                // look like one that is.
                .andExpect(content().string(containsString("manual")));
    }

    @Test
    void boardShowsSilenceRatherThanElapsedTime() throws Exception {
        // The run started 50 minutes ago and last spoke 2 minutes ago. The board
        // must report the 2 minutes: elapsed time says nothing about whether an
        // agent is alive, which is the mistake that killed working runs.
        mockMvc.perform(get("/boards/view-test"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("quiet 2m")))
                .andExpect(content().string(not(containsString("quiet 50m"))));
    }

    @Test
    void cardDetailShowsHistory() throws Exception {
        mockMvc.perform(get("/cards/" + cardId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Bound a turn by silence")))
                .andExpect(content().string(containsString("petri/9-bound-by-silence")))
                .andExpect(content().string(containsString("ses_view0000000001")))
                .andExpect(content().string(containsString("queued")));
    }

    @Test
    void unknownBoardIsNotFound() throws Exception {
        mockMvc.perform(get("/boards/does-not-exist")).andExpect(status().isNotFound());
    }

    @Test
    void unknownCardIsNotFound() throws Exception {
        mockMvc.perform(get("/cards/999999")).andExpect(status().isNotFound());
    }
}
