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
import tech.wenisch.petri.entity.Board;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.repository.BoardRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Creating a board from the browser - the one thing that used to need a curl
 * command before a person could do anything else with Petri at all.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NewBoardTests {

    @Autowired private WebApplicationContext context;
    @Autowired private BoardRepository boards;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void viewingTheFormRequiresSigningIn() throws Exception {
        mockMvc.perform(get("/boards/new"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void theFormIsReachableToASignedInPerson() throws Exception {
        mockMvc.perform(get("/boards/new").with(user("admin").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("New board")));
    }

    @Test
    void submittingCreatesTheBoardAndGoesStraightToItsPipeline() throws Exception {
        mockMvc.perform(post("/boards/new")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("name", "Control Panel")
                        .param("slug", "control-panel")
                        .param("forge", "FORGEJO")
                        .param("repository", "example/controlpanel")
                        .param("defaultBranch", "main"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/boards/control-panel/pipeline"));

        Board board = boards.findBySlug("control-panel").orElseThrow();
        assertThat(board.getName()).isEqualTo("Control Panel");
        assertThat(board.getForge()).isEqualTo(Forge.FORGEJO);
        assertThat(board.getRepository()).isEqualTo("example/controlpanel");
    }

    @Test
    void aMalformedSlugIsRefusedWithTheFieldsPreserved() throws Exception {
        mockMvc.perform(post("/boards/new")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("name", "Bad Slug")
                        .param("slug", "Not A Slug!")
                        .param("forge", "FORGEJO")
                        .param("repository", "example/controlpanel"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/boards/new"))
                .andExpect(flash().attributeExists("error"))
                .andExpect(flash().attribute("name", "Bad Slug"));

        assertThat(boards.findBySlug("Not A Slug!")).isEmpty();
    }

    @Test
    void aTakenSlugIsRefused() throws Exception {
        Board existing = new Board();
        existing.setSlug("taken");
        existing.setName("Existing");
        existing.setForge(Forge.FORGEJO);
        existing.setRepository("example/existing");
        existing.setDefaultBranch("main");
        boards.save(existing);

        mockMvc.perform(post("/boards/new")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("name", "Duplicate")
                        .param("slug", "taken")
                        .param("forge", "FORGEJO")
                        .param("repository", "example/duplicate"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/boards/new"))
                .andExpect(flash().attributeExists("error"));

        assertThat(boards.findBySlug("taken").orElseThrow().getName()).isEqualTo("Existing");
    }

    @Test
    void creatingABoardRequiresSigningIn() throws Exception {
        mockMvc.perform(post("/boards/new").with(csrf())
                        .param("name", "Hijacked")
                        .param("slug", "hijacked")
                        .param("forge", "FORGEJO")
                        .param("repository", "not/mine"))
                .andExpect(status().is3xxRedirection());

        assertThat(boards.findBySlug("hijacked")).isEmpty();
    }
}
