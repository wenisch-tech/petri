package tech.wenisch.petri.gateway;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Talks to an opencode-style gateway over HTTP.
 *
 * <p>Two endpoints matter. A repository shim starts work against a checkout the
 * gateway owns, and the session API reports what that work is doing. Petri never
 * reaches past either into git.
 */
public class HttpAgentGateway implements AgentGateway {

    private static final Logger LOG = LoggerFactory.getLogger(HttpAgentGateway.class);

    private final RestClient client;

    public HttpAgentGateway(RestClient client) {
        this.client = client;
    }

    @Override
    public String start(StartRequest request) {
        Map<String, Object> body = Map.of(
                "repository", request.repository(),
                "branch", request.branch(),
                "prompt", request.prompt());

        Map<String, Object> response = client.post()
                .uri("/run/async")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        Object sessionId = response == null ? null : response.get("sessionId");
        if (sessionId == null) {
            throw new GatewayException("gateway accepted the run but returned no session id");
        }
        return sessionId.toString();
    }

    @Override
    public Map<String, SessionSnapshot> observe(List<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }

        // One call for every session rather than one per run: the gateway
        // reports them together, and a poll that scales with the board is a
        // poll that eventually stops being run often enough.
        Map<String, Object> response = client.get()
                .uri("/session/status")
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});

        Map<String, SessionSnapshot> snapshots = new HashMap<>();
        for (String id : sessionIds) {
            Object raw = response == null ? null : response.get(id);
            snapshots.put(id, raw instanceof Map<?, ?> entry ? parse(id, entry)
                    : SessionSnapshot.unknown(id));
        }
        return snapshots;
    }

    private SessionSnapshot parse(String sessionId, Map<?, ?> entry) {
        Object rawType = entry.get("type");
        String type = (rawType == null ? "unknown" : rawType.toString()).toUpperCase();
        SessionState state;
        try {
            state = SessionState.valueOf(type);
        } catch (IllegalArgumentException ex) {
            LOG.debug("Unrecognised session state '{}' for {}", type, sessionId);
            state = SessionState.UNKNOWN;
        }

        Instant lastEventAt = null;
        Object at = entry.get("lastEventAt");
        if (at instanceof Number millis) {
            lastEventAt = Instant.ofEpochMilli(millis.longValue());
        } else if (at instanceof String text && !text.isBlank()) {
            try {
                lastEventAt = Instant.parse(text);
            } catch (RuntimeException ex) {
                LOG.debug("Unparsable lastEventAt '{}' for {}", text, sessionId);
            }
        }

        Object message = entry.get("message");
        return new SessionSnapshot(sessionId, state, lastEventAt,
                message == null ? null : message.toString());
    }

    @Override
    public void abort(String sessionId) {
        try {
            client.post().uri("/session/{id}/abort", sessionId).retrieve().toBodilessEntity();
        } catch (RuntimeException ex) {
            // A session that has already finished cannot be aborted, and that is
            // the common case when a stop races a completion.
            LOG.debug("Abort of session {} did not apply: {}", sessionId, ex.toString());
        }
    }
}
