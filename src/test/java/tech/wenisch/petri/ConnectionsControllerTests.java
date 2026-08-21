package tech.wenisch.petri;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The Connections screen: read-only configuration, plus a live reachability
 * check per target.
 *
 * <p>Nothing here is configured in the test profile, so every test action
 * exercises the "not configured" path rather than reaching a real network -
 * deliberately, since a test suite that depends on an outbound connection is
 * not a test suite that runs reliably.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConnectionsControllerTests {

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void viewingRequiresSigningIn() throws Exception {
        mockMvc().perform(get("/settings/connections"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void thePageListsEveryConfiguredTarget() throws Exception {
        mockMvc().perform(get("/settings/connections").with(user("admin").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Agent gateway")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Reviewing model")));
    }

    @Test
    void secretsAreNeverRenderedOnThePage() throws Exception {
        mockMvc().perform(get("/settings/connections").with(user("admin").roles("VIEWER")))
                .andExpect(status().isOk())
                // Nothing in test config sets a real secret, but the page must
                // never have a code path that prints the raw value at all -
                // "set" or "not set" only.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("petri.gateway.password"))));
    }

    @Test
    void testingAnUnconfiguredGatewaySaysSo() throws Exception {
        mockMvc().perform(post("/settings/connections/test/gateway")
                        .with(user("admin").roles("VIEWER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings/connections"))
                .andExpect(flash().attribute("gatewayOk", false));
    }

    @Test
    void testingAnUnconfiguredReviewModelSaysSo() throws Exception {
        mockMvc().perform(post("/settings/connections/test/review")
                        .with(user("admin").roles("VIEWER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("reviewOk", false));
    }

    @Test
    void testingAnUnconfiguredForgeSaysSo() throws Exception {
        mockMvc().perform(post("/settings/connections/test/forge/FORGEJO")
                        .with(user("admin").roles("VIEWER")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("forgeOk", false))
                .andExpect(flash().attribute("testedForge", tech.wenisch.petri.entity.Forge.FORGEJO));
    }

    @Test
    void testingRequiresSigningIn() throws Exception {
        mockMvc().perform(post("/settings/connections/test/gateway").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
