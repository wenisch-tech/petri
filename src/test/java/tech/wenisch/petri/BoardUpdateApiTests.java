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

/** {@code PUT /api/boards/{slug}}: the script-driven twin of the settings form. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "petri.security.api-key=board-update-test-key")
@Transactional
class BoardUpdateApiTests {

    private static final String TOKEN = "Bearer board-update-test-key";

    @Autowired private WebApplicationContext context;
    @Autowired private BoardRepository boards;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        Board board = new Board();
        board.setSlug("board-api-update");
        board.setName("Original");
        board.setForge(Forge.FORGEJO);
        board.setRepository("example/original");
        board.setDefaultBranch("main");
        boards.save(board);
    }

    @Test
    void aTokenHoldingScriptCanUpdateABoard() throws Exception {
        mockMvc.perform(put("/api/boards/board-api-update")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renamed","forge":"GITLAB",
                                 "repository":"example/renamed","defaultBranch":"trunk","enabled":false}
                                """))
                .andExpect(status().isOk());

        Board updated = boards.findBySlug("board-api-update").orElseThrow();
        assertThat(updated.getName()).isEqualTo("Renamed");
        assertThat(updated.getForge()).isEqualTo(Forge.GITLAB);
        assertThat(updated.getRepository()).isEqualTo("example/renamed");
        assertThat(updated.getDefaultBranch()).isEqualTo("trunk");
        assertThat(updated.isEnabled()).isFalse();
    }

    @Test
    void updatingAnUnknownBoardIsNotFound() throws Exception {
        mockMvc.perform(put("/api/boards/no-such-board")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x","forge":"FORGEJO","repository":"x/y"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRequestWithNoTokenIsRejected() throws Exception {
        mockMvc.perform(put("/api/boards/board-api-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Hijacked","forge":"FORGEJO","repository":"not/mine"}
                                """))
                .andExpect(status().isUnauthorized());

        assertThat(boards.findBySlug("board-api-update").orElseThrow().getName())
                .isEqualTo("Original");
    }
}
