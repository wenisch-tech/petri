package tech.wenisch.petri.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.forge.ForgeProperties;
import tech.wenisch.petri.gateway.GatewayProperties;
import tech.wenisch.petri.review.ReviewProperties;

/**
 * Live reachability checks for the Connections screen.
 *
 * <p>Deliberately separate from {@link tech.wenisch.petri.gateway.AgentGateway}
 * and {@link tech.wenisch.petri.forge.ForgeClient} rather than reusing them. The
 * gateway's {@code observe} short-circuits to an empty map for an empty session
 * list without ever reaching the network - correct for its own purpose, useless
 * as a reachability probe - and stretching either interface to double as a
 * health check would put a UI convenience inside the contract that drives real
 * work. This makes its own bounded call instead, with its own short timeout, so
 * a misconfigured host cannot make a person wait on the settings page any
 * longer than the ten seconds this is worth.
 */
@Service
public class ConnectionTestService {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionTestService.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private final GatewayProperties gateway;
    private final ForgeProperties forge;
    private final ReviewProperties review;

    public ConnectionTestService(GatewayProperties gateway, ForgeProperties forge, ReviewProperties review) {
        this.gateway = gateway;
        this.forge = forge;
        this.review = review;
    }

    /** Whether the probe reached something, and what it found or what went wrong. */
    public record Result(boolean ok, String detail) {
    }

    public Result testGateway() {
        if (gateway.baseUrl().isBlank()) {
            return new Result(false, "no petri.gateway.base-url configured");
        }
        try {
            RestClient client = basicAuthClient(gateway.baseUrl(), gateway.username(), gateway.password());
            HttpStatusCode status = client.get().uri("/session/status")
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            return new Result(true, "reachable (HTTP " + status.value() + ")");
        } catch (RestClientException ex) {
            return failure("gateway", ex);
        }
    }

    public Result testForge(Forge forgeType) {
        ForgeProperties.Instance instance = forge.getForge().get(forgeType);
        if (instance == null || !instance.configured()) {
            return new Result(false, "not configured");
        }
        if (forgeType != Forge.FORGEJO) {
            // Only Forgejo's client is implemented at all (see ForgeConfig); a
            // "test" for a forge Petri cannot otherwise talk to would just be a
            // second, disconnected claim about reachability.
            return new Result(false, "no client implemented for " + forgeType + " yet");
        }
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(instance.getBaseUrl() + "/api/v1")
                    .requestFactory(timeoutFactory())
                    .build();
            Map<String, Object> body = client.get().uri("/version").retrieve().body(JSON_MAP);
            Object version = body == null ? null : body.get("version");
            return new Result(true, version == null ? "reachable" : "reachable, version " + version);
        } catch (RestClientException ex) {
            return failure(forgeType.name(), ex);
        }
    }

    public Result testReview() {
        if (!review.configured()) {
            return new Result(false, "no petri.review.base-url configured");
        }
        try {
            RestClient.Builder builder = RestClient.builder()
                    .baseUrl(review.baseUrl())
                    .requestFactory(timeoutFactory());
            if (!review.apiKey().isBlank()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + review.apiKey());
            }
            HttpStatusCode status = builder.build().get().uri("/models")
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
            return new Result(true, "reachable (HTTP " + status.value() + ")");
        } catch (RestClientException ex) {
            return failure("review model", ex);
        }
    }

    private Result failure(String what, RestClientException ex) {
        LOG.debug("Connection test for {} failed: {}", what, ex.toString());
        return new Result(false, ex.getMostSpecificCause().getMessage());
    }

    private RestClient basicAuthClient(String baseUrl, String username, String password) {
        String credentials = username + ":" + password;
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeoutFactory())
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic)
                .build();
    }

    private SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(PROBE_TIMEOUT);
        factory.setReadTimeout(PROBE_TIMEOUT);
        return factory;
    }
}
