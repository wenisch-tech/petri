package tech.wenisch.petri;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tech.wenisch.petri.repository.PetriSettingsRepository;
import tech.wenisch.petri.service.PolicySettingsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The Policy screen: an editable overlay on top of environment defaults. */
@SpringBootTest
@ActiveProfiles("test")
class PolicyControllerTests {

    @Autowired private WebApplicationContext context;
    @Autowired private PetriSettingsRepository repository;
    @Autowired private PolicySettingsService settings;

    private MockMvc mockMvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void viewingRequiresSigningIn() throws Exception {
        mockMvc().perform(get("/settings/policy"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void thePageShowsTheCurrentlyEffectiveValues() throws Exception {
        mockMvc().perform(get("/settings/policy").with(user("admin").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/workspaces/petri")));
    }

    @Test
    void savingTakesEffectImmediately() throws Exception {
        mockMvc().perform(post("/settings/policy")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("maxConcurrentRuns", "5")
                        .param("idleTimeout", "PT20M")
                        .param("workspaceRoot", "/elsewhere")
                        .param("branchPrefix", "custom/")
                        .param("protectedPaths", "a/**,b/**"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings/policy"))
                .andExpect(flash().attribute("saved", true));

        assertThat(settings.maxConcurrentRuns()).isEqualTo(5);
        assertThat(settings.workspaceRoot()).isEqualTo("/elsewhere");
        assertThat(settings.branchPrefix()).isEqualTo("custom/");
        assertThat(settings.protectedPaths()).containsExactly("a/**", "b/**");
    }

    @Test
    void anUnparsableDurationIsRefusedRatherThanStored() throws Exception {
        mockMvc().perform(post("/settings/policy")
                        .with(user("admin").roles("VIEWER")).with(csrf())
                        .param("idleTimeout", "fifteen minutes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/settings/policy"))
                .andExpect(flash().attributeExists("error"));

        // Still the environment default: the bad value never reached the row.
        assertThat(settings.idleTimeout()).isEqualTo(java.time.Duration.ofMinutes(15));
    }

    @Test
    void savingRequiresSigningIn() throws Exception {
        mockMvc().perform(post("/settings/policy").with(csrf())
                        .param("maxConcurrentRuns", "99"))
                .andExpect(status().is3xxRedirection());

        assertThat(settings.maxConcurrentRuns()).isEqualTo(1);
    }
}
