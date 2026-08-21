package tech.wenisch.petri.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection details for the agent gateway, plus the bounds a run is held to.
 *
 * @param baseUrl        session API root, e.g. {@code http://gateway:4096}
 * @param repoApiUrl     repository shim root, e.g. {@code http://gateway:4098}.
 *                       A separate service: it holds the credential and the push
 *                       gate, and is deliberately not exposed where the session
 *                       API is
 * @param workspaceTemplate where the gateway keeps a checkout. Supports
 *                       {@code {owner}}, {@code {name}} and {@code {repository}};
 *                       the layout belongs to the gateway, so it is configured
 *                       rather than assumed here
 * @param username       basic-auth user
 * @param password       basic-auth password
 * @param enabled        on by default; set false to pause the runner without
 *                       discarding the rest of the configuration, which is what
 *                       you want during maintenance. A blank base URL leaves the
 *                       runner idle regardless, because there is nothing to call
 * @param requestTimeout per-request timeout; this bounds a single HTTP call, not
 *                       a turn
 * @param idleTimeout    how long a run may produce nothing before it is stopped.
 *                       This is the bound that matters. Wall-clock elapsed time
 *                       is not a progress signal: a single model call on a
 *                       contended GPU was measured at 278 seconds, so a turn
 *                       making several of them legitimately runs for many
 *                       minutes while remaining perfectly healthy.
 * @param maxDuration    hard ceiling, so a run that keeps emitting cannot occupy
 *                       the agent forever
 * @param startupGrace   how long a freshly started run may be absent from the
 *                       gateway's status before it is considered finished. The
 *                       gateway lists only sessions that are busy, so a run is
 *                       invisible between being created and producing its first
 *                       output; without this it would be concluded instantly
 */
@ConfigurationProperties(prefix = "petri.gateway")
public record GatewayProperties(
        String baseUrl,
        String repoApiUrl,
        String workspaceTemplate,
        String username,
        String password,
        Boolean enabled,
        Duration requestTimeout,
        Duration idleTimeout,
        Duration maxDuration,
        Duration startupGrace) {

    public GatewayProperties {
        baseUrl = baseUrl == null ? "" : baseUrl;
        repoApiUrl = repoApiUrl == null ? "" : repoApiUrl;
        workspaceTemplate = workspaceTemplate == null || workspaceTemplate.isBlank()
                ? "/data/workspaces/{owner}__{name}" : workspaceTemplate;
        // Defaulted here as well as in application.properties so the two agree
        // even if the property is removed; an absent primitive boolean would
        // otherwise bind to false and quietly disable the runner.
        enabled = enabled == null || enabled;
        username = username == null ? "petri" : username;
        password = password == null ? "" : password;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        idleTimeout = idleTimeout == null ? Duration.ofMinutes(15) : idleTimeout;
        maxDuration = maxDuration == null ? Duration.ofHours(1) : maxDuration;
        startupGrace = startupGrace == null ? Duration.ofMinutes(2) : startupGrace;
    }
}
