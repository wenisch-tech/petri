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
 * A board's own settings, edited by a signed-in person.
 *
 * <p>The slug is the one field the form never sends: it is the board's URL, and
 * silently accepting a new one here would move that URL out from under anyone
 * who bookmarked or scripted against it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BoardSettingsTests {

    @Autowired private WebApplicationContext context;
    @Autowired private BoardRepository boards;

    private MockMvc mockMvc;
    private Board board;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        board = new Board();
        board.setSlug("board-settings");
        board.setName("Board Settings");
        board.setForge(Forge.FORGEJO);
        board.setRepository("example/controlpanel");
        board.setDefaultBranch("main");
        boards.save(board);
    }

    @Test
    void thePageShowsTheCurrentSettings() throws Exception {
        mockMvc.perform(get("/boards/board-settings/settings")
                        .with(user("admin").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("example/controlpanel")));
    }

    @Test
    void viewingSettingsRequiresSigningIn() throws Exception {
        mockMvc.perform(get("/boards/board-settings/settings"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void savingUpdatesTheBoardAndRedirectsWithAConfirmation() throws Exception {
        mockMvc.perform(post("/boards/board-settings/settings")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("name", "Renamed")
                        .param("forge", "GITHUB")
                        .param("repository", "example/renamed")
                        .param("defaultBranch", "trunk")
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/boards/board-settings"))
                .andExpect(flash().attribute("saved", true));

        Board updated = boards.findBySlug("board-settings").orElseThrow();
        assertThat(updated.getName()).isEqualTo("Renamed");
        assertThat(updated.getForge()).isEqualTo(Forge.GITHUB);
        assertThat(updated.getRepository()).isEqualTo("example/renamed");
        assertThat(updated.getDefaultBranch()).isEqualTo("trunk");
        assertThat(updated.isEnabled()).isTrue();
        // The slug itself never moved.
        assertThat(updated.getSlug()).isEqualTo("board-settings");
    }

    @Test
    void anUncheckedEnabledBoxTurnsTheBoardOff() throws Exception {
        // An unchecked HTML checkbox sends no parameter at all - absence must
        // mean "off", not "leave it running".
        mockMvc.perform(post("/boards/board-settings/settings")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("name", "Board Settings")
                        .param("forge", "FORGEJO")
                        .param("repository", "example/controlpanel"))
                .andExpect(status().is3xxRedirection());

        assertThat(boards.findBySlug("board-settings").orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void savingRequiresSigningIn() throws Exception {
        mockMvc.perform(post("/boards/board-settings/settings").with(csrf())
                        .param("name", "Hijacked")
                        .param("forge", "GITHUB")
                        .param("repository", "not/mine"))
                .andExpect(status().is3xxRedirection());

        assertThat(boards.findBySlug("board-settings").orElseThrow().getName())
                .isEqualTo("Board Settings");
    }
}
