package tech.wenisch.petri;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.repository.ConnectionSettingsRepository;
import tech.wenisch.petri.repository.ForgeConnectionSettingsRepository;
import tech.wenisch.petri.service.ConnectionSettingsService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The merge between the connection overlay and the environment, and the rule
 * that keeps a secret from being wiped by an unrelated edit.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "petri.gateway.password=env-password-never-overridable",
        "petri.gateway.base-url=http://env-gateway.example",
})
class ConnectionSettingsTests {

    @Autowired private ConnectionSettingsService settings;
    @Autowired private ConnectionSettingsRepository repository;
    @Autowired private ForgeConnectionSettingsRepository forgeRepository;

    @AfterEach
    void clean() {
        repository.deleteAll();
        forgeRepository.deleteAll();
    }

    @Test
    void withNoOverlayEverythingReadsAsTheEnvironmentDefault() {
        assertThat(settings.effectiveGateway().baseUrl()).isEqualTo("http://env-gateway.example");
        assertThat(settings.effectiveGateway().password()).isEqualTo("env-password-never-overridable");
        assertThat(settings.configuredForges()).isEmpty();
    }

    @Test
    void thePasswordAlwaysComesFromTheEnvironmentEvenAfterOtherFieldsAreOverridden() {
        settings.updateGateway("http://overridden.example", "carol", true);

        assertThat(settings.effectiveGateway().baseUrl()).isEqualTo("http://overridden.example");
        assertThat(settings.effectiveGateway().username()).isEqualTo("carol");
        // Nothing in updateGateway's signature can even express a password -
        // this is the property actually being tested.
        assertThat(settings.effectiveGateway().password()).isEqualTo("env-password-never-overridable");
    }

    @Test
    void clearingTheGatewayBaseUrlRestoresTheEnvironmentDefault() {
        settings.updateGateway("http://overridden.example", null, null);
        assertThat(settings.effectiveGateway().baseUrl()).isEqualTo("http://overridden.example");

        settings.updateGateway("", null, null);
        assertThat(settings.effectiveGateway().baseUrl()).isEqualTo("http://env-gateway.example");
    }

    @Test
    void aForgeAddedThroughTheOverlayNeedsNoEnvironmentEntryAtAll() {
        assertThat(settings.effectiveForge(Forge.FORGEJO).configured()).isFalse();

        settings.updateForge(Forge.FORGEJO, "https://git.example.com", "a-token",
                false, true, null, false, null);

        var effective = settings.effectiveForge(Forge.FORGEJO);
        assertThat(effective.configured()).isTrue();
        assertThat(effective.baseUrl()).isEqualTo("https://git.example.com");
        assertThat(effective.token()).isEqualTo("a-token");
        assertThat(effective.handTokenToAgent()).isTrue();
        // No separate agent token was given, so the agent gets the one above.
        assertThat(effective.credentialForAgent()).isEqualTo("a-token");
        assertThat(settings.configuredForges()).containsExactly(Forge.FORGEJO);
    }

    @Test
    void aBlankTokenOnSaveLeavesTheStoredOneUntouched() {
        settings.updateForge(Forge.FORGEJO, "https://git.example.com", "keep-me",
                false, null, null, false, null);

        // Only the base URL is being changed here; the token field arrives
        // blank because a secret is never sent back to render into a form.
        settings.updateForge(Forge.FORGEJO, "https://git.example.com/renamed", null,
                false, null, null, false, null);

        assertThat(settings.effectiveForge(Forge.FORGEJO).token()).isEqualTo("keep-me");
        assertThat(settings.effectiveForge(Forge.FORGEJO).baseUrl())
                .isEqualTo("https://git.example.com/renamed");
    }

    @Test
    void theClearFlagRemovesTheTokenEvenWithNothingTyped() {
        settings.updateForge(Forge.FORGEJO, "https://git.example.com", "remove-me",
                false, null, null, false, null);

        settings.updateForge(Forge.FORGEJO, "https://git.example.com", null,
                true, null, null, false, null);

        assertThat(settings.effectiveForge(Forge.FORGEJO).token()).isEmpty();
    }

    @Test
    void theSameBlankMeansUnchangedRuleAppliesToTheReviewApiKey() {
        settings.updateReview("http://review.example", "keep-this-key", false, "chatgpt");
        settings.updateReview("http://review.example", null, false, "gpt-5");

        assertThat(settings.effectiveReview().apiKey()).isEqualTo("keep-this-key");
        assertThat(settings.effectiveReview().model()).isEqualTo("gpt-5");

        settings.updateReview("http://review.example", null, true, "gpt-5");
        assertThat(settings.effectiveReview().apiKey()).isEmpty();
    }
}
