package tech.wenisch.petri;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.wenisch.petri.gateway.GatewayProperties;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The shipped defaults, asserted so a stray edit cannot quietly change them. */
@SpringBootTest
@ActiveProfiles("test")
class GatewayDefaultsTests {

    @Autowired
    private GatewayProperties properties;

    @Test
    void theGatewayIsEnabledByDefault() {
        assertThat(properties.enabled())
                .as("an install should be ready to drive an agent without extra configuration")
                .isTrue();
    }

    @Test
    void anAbsentEnabledFlagStillMeansEnabled() {
        // A record with a primitive boolean would bind an absent property to
        // false and disable the runner silently. Boolean plus this default keeps
        // the code and application.properties saying the same thing.
        GatewayProperties defaults =
                new GatewayProperties(null, null, null, null, null, null, null);

        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.baseUrl()).isEmpty();
        assertThat(defaults.username()).isEqualTo("petri");
    }

    @Test
    void runsAreBoundedBySilenceNotByElapsedTime() {
        // The idle bound is the one that stops a run; the ceiling only exists so
        // a run that keeps emitting cannot occupy the agent forever. If these
        // were ever equal, silence would stop being the deciding signal.
        assertThat(properties.idleTimeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.maxDuration()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.idleTimeout()).isLessThan(properties.maxDuration());
    }
}
