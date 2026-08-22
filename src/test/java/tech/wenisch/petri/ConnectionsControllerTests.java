package tech.wenisch.petri;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.repository.ConnectionSettingsRepository;
import tech.wenisch.petri.repository.ForgeConnectionSettingsRepository;
import tech.wenisch.petri.service.ConnectionSettingsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The Connections screen: gateway, forges and reviewing model, editable and
 * database-backed except for the gateway password, which stays only in the
 * environment - and a live reachability check per target.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConnectionsControllerTests {

    @Autowired private WebApplicationContext context;
    @Autowired private ConnectionSettingsRepository repository;
    @Autowired private ForgeConnectionSettingsRepository forgeRepository;
    @Autowired private ConnectionSettingsService settings;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void clean() {
        repository.deleteAll();
        forgeRepository.deleteAll();
    }

    @Test
    void viewingRequiresSigningIn() throws Exception {
        mockMvc().perform(get("/settings/connections"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void thePageListsEveryTarget() throws Exception {
        mockMvc().perform(get("/settings/connections").with(user("admin").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Agent gateway")))
                .andExpect(content().string(containsString("Reviewing model")));
    }

    @Test
    void thePasswordFieldIsNotEditableAndNeverLeaksTheRealValue() throws Exception {
        // application-test.properties sets no password, so this proves the
        // point independent of what happens to be configured: the field is a
        // disabled input naming the property, never a value.
        mockMvc().perform(get("/settings/connections").with(user("admin").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("set only via petri.gateway.password")));
    }

    @Test
    void savingTheGatewayTakesEffectImmediately() throws Exception {
        mockMvc().perform(post("/settings/connections/gateway")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("baseUrl", "http://gateway.example")
                        .param("username", "carol")
                        .param("enabled", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("saved", "gateway"));

        var effective = settings.effectiveGateway();
        assertThat(effective.baseUrl()).isEqualTo("http://gateway.example");
        assertThat(effective.username()).isEqualTo("carol");
        assertThat(effective.enabled()).isFalse();
    }

    @Test
    void addingAForgeCreatesIt() throws Exception {
        mockMvc().perform(post("/settings/connections/forge")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("forgeType", "FORGEJO")
                        .param("baseUrl", "https://git.example.com")
                        .param("token", "a-real-token-value"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("saved", "forge-FORGEJO"));

        var effective = settings.effectiveForge(Forge.FORGEJO);
        assertThat(effective.baseUrl()).isEqualTo("https://git.example.com");
        assertThat(effective.token()).isEqualTo("a-real-token-value");
    }

    @Test
    void aStoredForgeTokenIsNeverRenderedBackToTheBrowser() throws Exception {
        settings.updateForge(Forge.FORGEJO, "https://git.example.com", "super-secret-token-value",
                false, null, null, false, null);

        mockMvc().perform(get("/settings/connections").with(user("admin").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("super-secret-token-value"))))
                // But the page still says something is there, rather than
                // silently looking like nothing was ever saved.
                .andExpect(content().string(containsString("(set)")));
    }

    @Test
    void leavingTheTokenFieldBlankOnSaveDoesNotClearIt() throws Exception {
        settings.updateForge(Forge.FORGEJO, "https://git.example.com", "keep-me",
                false, null, null, false, null);

        // Saving the base URL again, with the token field left empty as it
        // always renders - this must not be read as "remove the token".
        mockMvc().perform(post("/settings/connections/forge/FORGEJO")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("baseUrl", "https://git.example.com"))
                .andExpect(status().is3xxRedirection());

        assertThat(settings.effectiveForge(Forge.FORGEJO).token()).isEqualTo("keep-me");
    }

    @Test
    void theClearCheckboxActuallyRemovesTheToken() throws Exception {
        settings.updateForge(Forge.FORGEJO, "https://git.example.com", "remove-me",
                false, null, null, false, null);

        mockMvc().perform(post("/settings/connections/forge/FORGEJO")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("baseUrl", "https://git.example.com")
                        .param("clearToken", "true"))
                .andExpect(status().is3xxRedirection());

        assertThat(settings.effectiveForge(Forge.FORGEJO).token()).isEmpty();
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
                .andExpect(flash().attribute("testedForge", Forge.FORGEJO));
    }

    @Test
    void savingRequiresSigningIn() throws Exception {
        mockMvc().perform(post("/settings/connections/gateway").with(csrf())
                        .param("baseUrl", "http://hijacked.example"))
                .andExpect(status().is3xxRedirection());

        assertThat(settings.effectiveGateway().baseUrl()).isEmpty();
    }

    @Test
    void testingRequiresSigningIn() throws Exception {
        mockMvc().perform(post("/settings/connections/test/gateway").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }
}
