package tech.wenisch.petri.service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.ConnectionSettings;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.entity.ForgeConnectionSettings;
import tech.wenisch.petri.forge.ForgeProperties;
import tech.wenisch.petri.gateway.GatewayProperties;
import tech.wenisch.petri.repository.ConnectionSettingsRepository;
import tech.wenisch.petri.repository.ForgeConnectionSettingsRepository;
import tech.wenisch.petri.review.ReviewProperties;

/**
 * The operator-editable connections - gateway, forges, reviewing model -
 * merged from a database overlay and environment defaults.
 *
 * <p>The same shape as {@link PolicySettingsService}, and for the same reason:
 * a row is read fresh on every call rather than cached, an override falls back
 * to the environment default the moment it is cleared rather than replacing it
 * permanently, and every client built from these values ({@code
 * LiveAgentGateway}, {@code ForgeClientRegistry}, {@code LiveReviewModel}) is
 * built fresh per call rather than once at startup - the whole point of moving
 * this here is that a saved change takes effect on the next call, not the next
 * restart.
 *
 * <p>One value is deliberately outside this merge: the gateway's password.
 * There is no column for it anywhere in this database, on purpose - it stays
 * only in whatever already holds it, an environment variable or a mounted
 * Secret, and {@link #effectiveGateway()} always reads it from {@link
 * GatewayProperties} regardless of what the overlay contains.
 */
@Service
public class ConnectionSettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionSettingsService.class);
    private static final Long ROW_ID = 1L;

    private final ConnectionSettingsRepository repository;
    private final ForgeConnectionSettingsRepository forgeRepository;
    private final GatewayProperties gatewayDefaults;
    private final ForgeProperties forgeDefaults;
    private final ReviewProperties reviewDefaults;

    public ConnectionSettingsService(ConnectionSettingsRepository repository,
                                     ForgeConnectionSettingsRepository forgeRepository,
                                     GatewayProperties gatewayDefaults,
                                     ForgeProperties forgeDefaults,
                                     ReviewProperties reviewDefaults) {
        this.repository = repository;
        this.forgeRepository = forgeRepository;
        this.gatewayDefaults = gatewayDefaults;
        this.forgeDefaults = forgeDefaults;
        this.reviewDefaults = reviewDefaults;
    }

    // ---------------------------------------------------------------- gateway

    /** What the gateway is reachable at right now, password always from the environment. */
    public record EffectiveGateway(String baseUrl, String username, String password,
                                   boolean enabled, Duration requestTimeout) {
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    public EffectiveGateway effectiveGateway() {
        ConnectionSettings row = row();
        return new EffectiveGateway(
                blankFallback(row.getGatewayBaseUrl(), gatewayDefaults.baseUrl()),
                blankFallback(row.getGatewayUsername(), gatewayDefaults.username()),
                gatewayDefaults.password(),
                row.getGatewayEnabled() != null ? row.getGatewayEnabled() : gatewayDefaults.enabled(),
                gatewayDefaults.requestTimeout());
    }

    @Transactional
    public void updateGateway(String baseUrl, String username, Boolean enabled) {
        ConnectionSettings row = row();
        row.setId(ROW_ID);
        row.setGatewayBaseUrl(blankToNull(baseUrl));
        row.setGatewayUsername(blankToNull(username));
        row.setGatewayEnabled(enabled);
        repository.save(row);
        LOG.info("Gateway connection settings updated");
    }

    // ------------------------------------------------------------------ forge

    /** What one forge is reachable at right now. */
    public record EffectiveForge(String baseUrl, String token, boolean handTokenToAgent,
                                 String agentToken, String agentUsername) {
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }

        /** The credential the agent should be given, or empty if it gets none. */
        public String credentialForAgent() {
            if (!handTokenToAgent) {
                return "";
            }
            return agentToken == null || agentToken.isBlank() ? token : agentToken;
        }
    }

    /** Every forge with something configured, from either the environment or the overlay. */
    public Set<Forge> configuredForges() {
        Set<Forge> forges = new LinkedHashSet<>(forgeDefaults.getForge().keySet());
        forgeRepository.findAll().forEach(row -> forges.add(row.getForge()));
        return forges;
    }

    public EffectiveForge effectiveForge(Forge forge) {
        ForgeProperties.Instance env = forgeDefaults.getForge().get(forge);
        String envBaseUrl = env == null ? "" : env.getBaseUrl();
        String envToken = env == null ? "" : env.getToken();
        boolean envHandToken = env != null && env.isHandTokenToAgent();
        String envAgentToken = env == null ? "" : env.getAgentToken();
        String envAgentUsername = env == null || env.getAgentUsername() == null
                ? "petri" : env.getAgentUsername();

        ForgeConnectionSettings override = forgeRepository.findById(forge).orElse(null);
        if (override == null) {
            return new EffectiveForge(envBaseUrl, envToken, envHandToken, envAgentToken, envAgentUsername);
        }
        return new EffectiveForge(
                blankFallback(override.getBaseUrl(), envBaseUrl),
                blankFallback(override.getToken(), envToken),
                override.getHandTokenToAgent() != null ? override.getHandTokenToAgent() : envHandToken,
                blankFallback(override.getAgentToken(), envAgentToken),
                blankFallback(override.getAgentUsername(), envAgentUsername));
    }

    /** The raw overlay row for a forge, or an empty one - for pre-filling the edit form. */
    public ForgeConnectionSettings currentForge(Forge forge) {
        return forgeRepository.findById(forge).orElseGet(() -> {
            ForgeConnectionSettings fresh = new ForgeConnectionSettings();
            fresh.setForge(forge);
            return fresh;
        });
    }

    /**
     * Update a forge's connection.
     *
     * <p>{@code token} and {@code agentToken} follow a different rule from every
     * other field here: a blank submission leaves whatever is already stored
     * alone, rather than clearing it. A secret is never sent back to the
     * browser to be re-submitted, so "blank" on a secret field means "the
     * operator didn't touch this", not "the operator wants it gone" - the two
     * explicit clear flags exist for when they do mean that.
     */
    @Transactional
    public void updateForge(Forge forge, String baseUrl, String token, boolean clearToken,
                            Boolean handTokenToAgent, String agentToken, boolean clearAgentToken,
                            String agentUsername) {
        ForgeConnectionSettings row = forgeRepository.findById(forge).orElseGet(() -> {
            ForgeConnectionSettings fresh = new ForgeConnectionSettings();
            fresh.setForge(forge);
            return fresh;
        });
        row.setBaseUrl(blankToNull(baseUrl));
        row.setToken(resolveSecret(row.getToken(), token, clearToken));
        row.setHandTokenToAgent(handTokenToAgent);
        row.setAgentToken(resolveSecret(row.getAgentToken(), agentToken, clearAgentToken));
        row.setAgentUsername(blankToNull(agentUsername));
        forgeRepository.save(row);
        LOG.info("Connection settings for forge {} updated", forge);
    }

    /** A secret field's next value: explicit clear wins, then a real submission, then no change. */
    private String resolveSecret(String stored, String submitted, boolean clear) {
        if (clear) {
            return null;
        }
        return submitted == null || submitted.isBlank() ? stored : submitted;
    }

    // ----------------------------------------------------------------- review

    public record EffectiveReview(String baseUrl, String apiKey, String model, Duration timeout) {
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    public EffectiveReview effectiveReview() {
        ConnectionSettings row = row();
        return new EffectiveReview(
                blankFallback(row.getReviewBaseUrl(), reviewDefaults.baseUrl()),
                blankFallback(row.getReviewApiKey(), reviewDefaults.apiKey()),
                blankFallback(row.getReviewModel(), reviewDefaults.model()),
                reviewDefaults.timeout());
    }

    /** Same blank-means-unchanged rule as {@link #updateForge} for {@code apiKey}. */
    @Transactional
    public void updateReview(String baseUrl, String apiKey, boolean clearApiKey, String model) {
        ConnectionSettings row = row();
        row.setId(ROW_ID);
        row.setReviewBaseUrl(blankToNull(baseUrl));
        row.setReviewApiKey(resolveSecret(row.getReviewApiKey(), apiKey, clearApiKey));
        row.setReviewModel(blankToNull(model));
        repository.save(row);
        LOG.info("Reviewing model connection settings updated");
    }

    // ------------------------------------------------------------------ misc

    /** The raw overlay row for gateway/review, or an empty one - for pre-filling the edit form. */
    @Transactional(readOnly = true)
    public ConnectionSettings current() {
        return row();
    }

    private ConnectionSettings row() {
        return repository.findById(ROW_ID).orElseGet(ConnectionSettings::new);
    }

    private String blankFallback(String override, String fallback) {
        return override == null || override.isBlank() ? fallback : override;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
