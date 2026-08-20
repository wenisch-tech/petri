package tech.wenisch.petri.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tech.wenisch.petri.gateway.AgentGateway;
import tech.wenisch.petri.gateway.DisabledAgentGateway;
import tech.wenisch.petri.gateway.GatewayProperties;
import tech.wenisch.petri.gateway.HttpAgentGateway;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfig {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayConfig.class);

    @Bean
    AgentGateway agentGateway(GatewayProperties properties) {
        if (!properties.enabled()) {
            LOG.info("Agent gateway switched off (petri.gateway.enabled=false); "
                    + "the runner will not start any work");
            return new DisabledAgentGateway();
        }
        if (properties.baseUrl().isBlank()) {
            // Enabled but unreachable is a configuration mistake, not a mode, so
            // say so plainly rather than logging "disabled" and contradicting
            // the setting the operator actually chose.
            LOG.warn("Agent gateway is enabled but petri.gateway.base-url is not set; "
                    + "no work can be started until it is");
            return new DisabledAgentGateway();
        }

        LOG.info("Agent gateway at {}", properties.baseUrl());
        return new HttpAgentGateway(client(properties));
    }

    /**
     * Built here rather than from an injected builder.
     *
     * <p>Timeouts are the point. Every call to the gateway must be bounded, or a
     * gateway that accepts a connection and then stops answering wedges the
     * poller that is supposed to be watching for exactly that condition.
     *
     * <p>This bounds a single HTTP call and nothing else. How long a <em>run</em>
     * may take is decided by silence, in {@code GatewayProperties.idleTimeout}.
     */
    private RestClient client(GatewayProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.requestTimeout());
        factory.setReadTimeout(properties.requestTimeout());

        String credentials = properties.username() + ":" + properties.password();
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic)
                .build();
    }
}
