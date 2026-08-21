package tech.wenisch.petri;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.forge.ForgeProperties;
import tech.wenisch.petri.forge.ForgejoClient;
import tech.wenisch.petri.service.Redactor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Handing a credential to the agent is a choice, and a reversible one.
 *
 * <p>Two properties are worth pinning. The switch must be off unless someone
 * turned it on, because the default decides who holds the push right in every
 * install that never read the documentation. And once a token has been handed
 * over it must not come back: the agent's output is stored and rendered, and an
 * agent that hits a git error quotes the URL it was given.
 */
class TokenHandoverTests {

    private static final String TOKEN = "40734c65deadbeefcafe0123456789abcdef0000";

    private ForgeProperties.Instance instance(boolean handOver) {
        ForgeProperties.Instance instance = new ForgeProperties.Instance();
        instance.setBaseUrl("https://git.example.com");
        instance.setToken(TOKEN);
        instance.setHandTokenToAgent(handOver);
        return instance;
    }

    private ForgejoClient client(ForgeProperties.Instance instance) {
        return new ForgejoClient(RestClient.builder().build(), instance.getBaseUrl(),
                instance.getAgentUsername(), instance.credentialForAgent());
    }

    @Test
    void handoverIsOffUntilItIsAskedFor() {
        assertThat(new ForgeProperties.Instance().isHandTokenToAgent()).isFalse();
        assertThat(instance(false).credentialForAgent()).isEmpty();

        assertThat(client(instance(false)).cloneUrlForAgent("example/controlpanel"))
                .isEqualTo("https://git.example.com/example/controlpanel.git")
                .doesNotContain(TOKEN);
    }

    @Test
    void withTheSwitchOnTheAgentGetsAUrlItCanPushWith() {
        String url = client(instance(true)).cloneUrlForAgent("example/controlpanel");

        assertThat(url).contains(TOKEN);
        assertThat(url).startsWith("https://petri:");
        assertThat(url).endsWith("@git.example.com/example/controlpanel.git");
    }

    @Test
    void aSeparateAgentTokenIsPreferredToPetrisOwn() {
        // The point of the separate setting: this one may push, Petri's own
        // needs only to read and to open a pull request.
        ForgeProperties.Instance instance = instance(true);
        instance.setAgentToken("push-only-token-0000");

        assertThat(instance.credentialForAgent()).isEqualTo("push-only-token-0000");
        assertThat(client(instance).cloneUrlForAgent("example/controlpanel"))
                .contains("push-only-token-0000")
                .doesNotContain(TOKEN);
    }

    @Test
    void theTokenNeverSurvivesInWhatTheAgentSaidBack() {
        ForgeProperties properties = new ForgeProperties();
        properties.getForge().put(Forge.FORGEJO, instance(true));
        Redactor redactor = new Redactor(properties);

        String complaint = "fatal: could not read from "
                + "https://petri:" + TOKEN + "@git.example.com/example/controlpanel.git";

        assertThat(redactor.redact(complaint))
                .doesNotContain(TOKEN)
                .contains("[REDACTED]")
                // Still legible: the point is to keep the error useful.
                .contains("could not read from");
    }

    @Test
    void aPercentEncodedTokenIsCaughtToo() {
        ForgeProperties.Instance instance = instance(true);
        instance.setAgentToken("tok/en+with=specials");
        ForgeProperties properties = new ForgeProperties();
        properties.getForge().put(Forge.FORGEJO, instance);

        // What comes back is what went out: the URL carried it encoded, so a
        // literal search for the raw token would walk straight past it.
        assertThat(new Redactor(properties).redact("remote: tok%2Fen%2Bwith%3Dspecials rejected"))
                .doesNotContain("tok%2Fen")
                .contains("[REDACTED]");
    }

    @Test
    void nothingIsRedactedWhenNoTokenIsConfigured() {
        Redactor redactor = new Redactor(new ForgeProperties());

        assertThat(redactor.redact("plain output")).isEqualTo("plain output");
        assertThat(redactor.redact(null)).isNull();
    }
}
