package tech.wenisch.petri;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.repository.PetriSettingsRepository;
import tech.wenisch.petri.service.PolicySettingsService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The merge between the database overlay and the environment default.
 *
 * <p>The point being tested throughout: a field nobody has ever set through the
 * Policy screen must read exactly as if the overlay did not exist, and setting
 * it back to blank must return it there - the database is a shadow over
 * configuration, never a replacement for it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PolicySettingsTests {

    @Autowired private PolicySettingsService settings;
    @Autowired private PetriSettingsRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void withNoOverlayRowEverythingReadsAsTheEnvironmentDefault() {
        assertThat(settings.maxConcurrentRuns()).isEqualTo(1);
        assertThat(settings.workspaceRoot()).isEqualTo("/workspaces/petri");
        assertThat(settings.branchPrefix()).isEqualTo("petri/");
        assertThat(settings.protectedPaths()).contains(".github/**");
        assertThat(settings.idleTimeout()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void anUpdateIsVisibleImmediately() {
        settings.update(3, "PT30M", null, null, null, null, null);

        assertThat(settings.maxConcurrentRuns()).isEqualTo(3);
        assertThat(settings.idleTimeout()).isEqualTo(Duration.ofMinutes(30));
        // Fields left null in the same update still fall through to the default.
        assertThat(settings.workspaceRoot()).isEqualTo("/workspaces/petri");
    }

    @Test
    void clearingAFieldBackToBlankRestoresTheDefault() {
        settings.update(3, null, null, null, "/elsewhere", "custom/", "a,b,c");
        assertThat(settings.workspaceRoot()).isEqualTo("/elsewhere");

        settings.update(3, null, null, null, "", "", "");
        assertThat(settings.workspaceRoot()).isEqualTo("/workspaces/petri");
        assertThat(settings.branchPrefix()).isEqualTo("petri/");
        assertThat(settings.protectedPaths()).contains(".github/**");
    }

    @Test
    void anUnparsableStoredDurationFallsBackRatherThanThrows() {
        // Reachable only if the row were edited outside the service, but a
        // gate evaluating every ten seconds must never be the place that finds
        // out a duration string is broken.
        settings.update(1, "not-a-duration", null, null, null, null, null);

        assertThat(settings.idleTimeout()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void protectedPathsSplitAndTrimOnCommas() {
        settings.update(1, null, null, null, null, null, " a/**, b/** ,c");

        assertThat(settings.protectedPaths()).containsExactly("a/**", "b/**", "c");
    }
}
