package tech.wenisch.petri;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.repository.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Editing a pipeline after it has been used.
 *
 * <p>The rule is narrow on purpose: a state a card is currently sitting in
 * cannot be removed. Everything else is fair game. Refusing every edit as soon
 * as a board had any card froze the pipeline at exactly the moment you find out
 * it needs another state.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "petri.security.api-key=pipeline-test-key")
@Transactional
class PipelineEditTests {

    private static final String TOKEN = "Bearer pipeline-test-key";

    @Autowired private WebApplicationContext context;
    @Autowired private BoardRepository boards;
    @Autowired private WorkflowStateRepository states;
    @Autowired private CardRepository cards;
    @Autowired private AgentRunRepository runs;

    private MockMvc mockMvc;
    private Board board;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        board = new Board();
        board.setSlug("edit-test");
        board.setName("Edit Test");
        board.setForge(Forge.FORGEJO);
        board.setRepository("example/controlpanel");
        board.setDefaultBranch("main");
        boards.save(board);
    }

    private WorkflowState state(String name, int position) {
        WorkflowState state = new WorkflowState();
        state.setBoard(board);
        state.setName(name);
        state.setPosition(position);
        state.setGate(GateType.NONE);
        state.setModelAlias("coding-agent");
        return states.save(state);
    }

    private Card cardIn(WorkflowState state) {
        Card card = new Card();
        card.setBoard(board);
        card.setState(state);
        card.setTitle("Existing work");
        return cards.save(card);
    }

    private String pipeline(String... names) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < names.length; i++) {
            json.append("{\"name\":\"").append(names[i]).append("\",\"position\":").append(i)
                    .append(",\"gate\":\"NONE\",\"modelAlias\":\"coding-agent\"}");
            if (i < names.length - 1) {
                json.append(',');
            }
        }
        return json.append(']').toString();
    }

    @Test
    void aStateCanBeAddedWhileCardsExist() throws Exception {
        WorkflowState implement = state("implement", 0);
        cardIn(implement);

        mockMvc.perform(put("/api/boards/edit-test/states")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pipeline("implement", "review")))
                .andExpect(status().isOk());

        assertThat(states.findByBoardOrderByPositionAsc(board))
                .extracting(WorkflowState::getName)
                .containsExactly("implement", "review");
    }

    @Test
    void removingAStateThatHoldsCardsIsRefusedByName() throws Exception {
        WorkflowState implement = state("implement", 0);
        state("review", 1);
        cardIn(implement);

        mockMvc.perform(put("/api/boards/edit-test/states")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pipeline("review")))
                .andExpect(status().isConflict());

        // Refused entirely: a partial edit would be worse than none.
        assertThat(states.findByBoardOrderByPositionAsc(board)).hasSize(2);
    }

    @Test
    void anEmptyStateCanBeRemovedWhileOtherStatesHoldCards() throws Exception {
        WorkflowState implement = state("implement", 0);
        state("obsolete", 1);
        cardIn(implement);

        mockMvc.perform(put("/api/boards/edit-test/states")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pipeline("implement")))
                .andExpect(status().isOk());

        assertThat(states.findByBoardOrderByPositionAsc(board))
                .extracting(WorkflowState::getName)
                .containsExactly("implement");
    }

    @Test
    void anEditedStateKeepsItsIdentityAndItsHistory() throws Exception {
        WorkflowState implement = state("implement", 0);
        Long stateId = implement.getId();
        Card card = cardIn(implement);

        AgentRun run = new AgentRun();
        run.setCard(card);
        run.setState(implement);
        run.setAttempt(1);
        run.setStatus(RunStatus.SUCCEEDED);
        runs.save(run);

        mockMvc.perform(put("/api/boards/edit-test/states")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{"name":"implement","position":0,"gate":"REPOSITORY",
                                  "modelAlias":"chatgpt","maxAttempts":5}]
                                """))
                .andExpect(status().isOk());

        // Updated in place rather than recreated, so the run and the card still
        // point at it. Replacing the row would orphan every reference to it.
        WorkflowState after = states.findById(stateId).orElseThrow();
        assertThat(after.getGate()).isEqualTo(GateType.REPOSITORY);
        assertThat(after.getModelAlias()).isEqualTo("chatgpt");
        assertThat(after.getMaxAttempts()).isEqualTo(5);
        assertThat(runs.findByCardOrderByIdDesc(card)).hasSize(1);
        assertThat(cards.findById(card.getId()).orElseThrow().getState().getId()).isEqualTo(stateId);
    }
}
