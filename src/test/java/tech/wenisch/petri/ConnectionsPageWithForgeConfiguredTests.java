package tech.wenisch.petri;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Connections page with an actual forge in the map, in its own class rather
 * than in {@link ConnectionsControllerTests}.
 *
 * <p>The test profile configures no forge at all, so {@code th:each} over an
 * empty map never touches the per-forge template fragment - a template bug
 * inside that loop (as {@code ForgeProperties.Instance} being a plain bean
 * rather than a record, read with the wrong accessor syntax, once was) would
 * pass every test in that class and still throw a 500 the first time a real
 * deployment configures a forge. This is the test that would have caught it.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "petri.forge.forgejo.base-url=http://forge.invalid")
class ConnectionsPageWithForgeConfiguredTests {

    @Autowired private WebApplicationContext context;

    @Test
    void thePageRendersTheConfiguredForgeWithoutError() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mockMvc.perform(get("/settings/connections").with(user("admin").roles("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("FORGEJO")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("http://forge.invalid")));
    }
}
