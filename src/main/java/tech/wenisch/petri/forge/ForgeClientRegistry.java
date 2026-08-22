package tech.wenisch.petri.forge;

import java.time.Duration;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.service.ConnectionSettingsService;

/**
 * Resolves a {@link ForgeClient} for a board's forge, fresh on every call.
 *
 * <p>Replaces what used to be a {@code Map<Forge, ForgeClient>} built once at
 * startup from environment properties. A plain map cannot be made to reflect a
 * change saved through the Connections screen without either caching it behind
 * something that knows when to invalidate, or - simpler, and what this does -
 * not caching it at all. {@link RestClient} is cheap to build; there is nothing
 * here worth the complexity of getting a cache invalidation rule right.
 *
 * <p>Only {@link Forge#FORGEJO} resolves to a real client. GitHub and GitLab fit
 * the same interface and would each be a small class, but shipping one written
 * from documentation alone rather than checked against a running instance would
 * mean shipping the failure mode this project keeps finding: something that
 * looks right and silently is not.
 */
@Component
public class ForgeClientRegistry {

    private final ConnectionSettingsService settings;
    private final Duration timeout;

    public ForgeClientRegistry(ConnectionSettingsService settings, ForgeProperties properties) {
        this.settings = settings;
        this.timeout = properties.getForgeTimeout();
    }

    public Optional<ForgeClient> get(Forge forge) {
        if (forge != Forge.FORGEJO) {
            return Optional.empty();
        }
        ConnectionSettingsService.EffectiveForge effective = settings.effectiveForge(forge);
        if (!effective.configured()) {
            return Optional.empty();
        }
        return Optional.of(new ForgejoClient(
                client(effective.baseUrl() + "/api/v1", effective.token()),
                effective.baseUrl(), effective.agentUsername(), effective.credentialForAgent()));
    }

    private RestClient client(String baseUrl, String token) {
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
