package tech.wenisch.petri;

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
import tech.wenisch.petri.repository.BoardRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/boards}: the script-driven twin of the "new board" page.
 * The slug rule is shared through {@code BoardService}, so this only needs to
 * confirm it actually reaches the API rather than re-proving the rule itself -
 * {@code BoardServiceTests} does that.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "petri.security.api-key=board-create-test-key")
@Transactional
class BoardCreateApiTests {

    private static final String TOKEN = "Bearer board-create-test-key";

    @Autowired private WebApplicationContext context;
    @Autowired private BoardRepository boards;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void aMalformedSlugIsRejected() throws Exception {
        mockMvc().perform(post("/api/boards")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"Not A Slug","name":"x","forge":"FORGEJO","repository":"x/y"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(boards.findBySlug("Not A Slug")).isEmpty();
    }

    @Test
    void aDuplicateSlugIsAConflict() throws Exception {
        mockMvc().perform(post("/api/boards")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"dup","name":"First","forge":"FORGEJO","repository":"x/y"}
                                """))
                .andExpect(status().isCreated());

        mockMvc().perform(post("/api/boards")
                        .header("Authorization", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"dup","name":"Second","forge":"FORGEJO","repository":"x/z"}
                                """))
                .andExpect(status().isConflict());

        assertThat(boards.findBySlug("dup").orElseThrow().getName()).isEqualTo("First");
    }
}
