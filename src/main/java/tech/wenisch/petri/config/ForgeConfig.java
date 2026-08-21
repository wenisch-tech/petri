package tech.wenisch.petri.config;

import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.forge.ForgeClient;
import tech.wenisch.petri.forge.ForgeProperties;
import tech.wenisch.petri.forge.ForgejoClient;

@Configuration
@EnableConfigurationProperties(ForgeProperties.class)
public class ForgeConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ForgeConfig.class);

    /**
     * One client per configured forge.
     *
     * <p>Only Forgejo and Gitea are implemented, and only because their API was
     * checked against a running instance. GitHub and GitLab fit the same
     * interface and would each be a small class - but shipping clients written
     * from documentation alone would mean shipping the failure mode this project
     * keeps finding: something that looks right and silently is not.
     */
    @Bean
    Map<Forge, ForgeClient> forgeClients(ForgeProperties properties) {
        Map<Forge, ForgeClient> clients = new EnumMap<>(Forge.class);

        properties.getForge().forEach((forge, instance) -> {
            if (!instance.configured()) {
                return;
            }
            if (forge != Forge.FORGEJO) {
                LOG.warn("No client implemented for {}; that board cannot be published", forge);
                return;
            }
            LOG.info("Forge {} at {}", forge, instance.getBaseUrl());
            clients.put(forge, new ForgejoClient(
                    client(instance.getBaseUrl() + "/api/v1", instance.getToken(),
                            properties.getTimeout()),
                    instance.getBaseUrl()));
        });

        if (clients.isEmpty()) {
            LOG.warn("No forge configured; Petri can drive agents but cannot read a diff "
                    + "or open a pull request");
        }
        return clients;
    }

    private RestClient client(String baseUrl, String token, java.time.Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory);
        if (token != null && !token.isBlank()) {
            // Forgejo's own scheme. Bearer works on some routes and not others.
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "token " + token);
        }
        return builder.build();
    }
}
