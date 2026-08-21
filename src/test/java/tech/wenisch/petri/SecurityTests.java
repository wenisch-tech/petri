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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security, with the filter chain actually applied.
 *
 * <p>The other web tests build MockMvc straight from the context, which does not
 * install the security filters - so every one of them passed unchanged after
 * authentication was switched on, and none of them said anything about it. This
 * class exists because that silence was indistinguishable from working.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "petri.security.api-key=test-key-not-a-real-secret",
        "petri.security.password=test-password"
})
@Transactional
class SecurityTests {

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void theBoardRequiresSigningIn() throws Exception {
        mockMvc.perform(get("/boards/anything")).andExpect(status().is3xxRedirection());
    }

    @Test
    void probesAnswerWithoutSigningIn() throws Exception {
        // Kubernetes cannot present a session, and a pod whose probe needs one
        // never becomes ready.
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void theWriteApiRejectsAnAbsentToken() throws Exception {
        mockMvc.perform(post("/api/boards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theWriteApiRejectsAWrongToken() throws Exception {
        mockMvc.perform(post("/api/boards")
                        .header("Authorization", "Bearer wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theWriteApiAcceptsTheConfiguredToken() throws Exception {
        mockMvc.perform(post("/api/boards")
                        .header("Authorization", "Bearer test-key-not-a-real-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"api-test","name":"API Test","forge":"FORGEJO",
                                 "repository":"example/controlpanel"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void aSessionCannotAuthoriseAWrite() throws Exception {
        // The API chain is token-only, so a signed-in browser cannot be induced
        // into queueing agent work.
        //
        // 403 rather than 401 here, and that is right: this caller was
        // recognised, it simply is not allowed to write. Unauthenticated and
        // authenticated-but-not-permitted are different answers, and collapsing
        // them would hide which one a client actually got.
        mockMvc.perform(post("/api/boards")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.user("admin").roles("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
