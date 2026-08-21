package tech.wenisch.petri;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.repository.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The pipeline editor page: the same rules as the API's {@code PUT .../states},
 * reached by a signed-in person instead of a script.
 *
 * <p>The browser builds JSON and posts it as one field; these tests post the
 * JSON directly, which is exactly what the page's own script hands the server -
 * the DOM manipulation and drag-reorder that produce it are the page's problem,
 * not the server's.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PipelineEditorPageTests {

    @Autowired private WebApplicationContext context;
    @Autowired private BoardRepository boards;
    @Autowired private WorkflowStateRepository states;
    @Autowired private CardRepository cards;

    private MockMvc mockMvc;
    private Board board;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        board = new Board();
        board.setSlug("pipeline-editor");
        board.setName("Pipeline Editor");
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

    @Test
    void thePageListsEveryStateFieldForASignedInPerson() throws Exception {
        state("implement", 0);

        mockMvc.perform(get("/boards/pipeline-editor/pipeline")
                        .with(user("admin").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("states"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("implement")));
    }

    @Test
    void viewingThePageRequiresSigningIn() throws Exception {
        mockMvc.perform(get("/boards/pipeline-editor/pipeline"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void savingTheWholePipelineReplacesIt() throws Exception {
        state("implement", 0);

        String json = """
                [{"name":"implement","position":0,"gate":"REPOSITORY","modelAlias":"coding-agent",
                  "nextOnPass":"review"},
                 {"name":"review","position":1,"gate":"LLM_VERDICT","modelAlias":"chatgpt"}]
                """;

        mockMvc.perform(post("/boards/pipeline-editor/pipeline")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("pipelineJson", json))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/boards/pipeline-editor"));

        assertThat(states.findByBoardOrderByPositionAsc(board))
                .extracting(WorkflowState::getName)
                .containsExactly("implement", "review");
    }

    @Test
    void removingAStateThatHoldsCardsRedirectsBackWithTheReason() throws Exception {
        WorkflowState implement = state("implement", 0);
        Card card = new Card();
        card.setBoard(board);
        card.setState(implement);
        card.setTitle("Existing work");
        cards.save(card);

        String json = """
                [{"name":"review","position":0,"gate":"NONE"}]
                """;

        mockMvc.perform(post("/boards/pipeline-editor/pipeline")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("pipelineJson", json))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/boards/pipeline-editor/pipeline"))
                .andExpect(flash().attributeExists("error"));

        // Refused entirely, same as the API: a partial edit is worse than none.
        assertThat(states.findByBoardOrderByPositionAsc(board))
                .extracting(WorkflowState::getName)
                .containsExactly("implement");
    }

    @Test
    void malformedJsonFromTheBrowserIsNotAServerError() throws Exception {
        state("implement", 0);

        mockMvc.perform(post("/boards/pipeline-editor/pipeline")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("pipelineJson", "{ not json"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/boards/pipeline-editor/pipeline"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    void savingRequiresSigningIn() throws Exception {
        state("implement", 0);

        mockMvc.perform(post("/boards/pipeline-editor/pipeline").with(csrf())
                        .param("pipelineJson", "[]"))
                .andExpect(status().is3xxRedirection());

        assertThat(states.findByBoardOrderByPositionAsc(board)).hasSize(1);
    }
}
