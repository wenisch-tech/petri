package tech.wenisch.petri.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import tech.wenisch.petri.forge.ForgeProperties;
import tech.wenisch.petri.gateway.GatewayProperties;
import tech.wenisch.petri.review.ReviewProperties;

/**
 * Registers the environment-sourced defaults that {@code
 * ConnectionSettingsService} overlays with the database.
 *
 * <p>This is where {@link GatewayProperties}, {@link ForgeProperties} and
 * {@link ReviewProperties} used to be built into concrete {@code AgentGateway},
 * {@code ForgeClient} and {@code ReviewModel} beans - once, at startup. That
 * construction now happens per call in {@code LiveAgentGateway}, {@code
 * ForgeClientRegistry} and {@code LiveReviewModel} instead, so a change saved
 * through the Connections screen takes effect on the next call rather than the
 * next restart. What is left here is only the environment binding itself.
 */
@Configuration
@EnableConfigurationProperties({GatewayProperties.class, ForgeProperties.class, ReviewProperties.class})
public class ConnectionPropertiesConfig {
}
