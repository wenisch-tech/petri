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
 * Talks to an opencode gateway.
 *
 * <p>Only the session API. Git is the agent's business: it clones and pushes
 * with its own credential, in a workspace Petri names but never reads.
 *
 * <p>Every path here was checked against a running gateway's OpenAPI document.
 * An earlier version invented {@code POST /run/async} and {@code POST /check} on
 * the session port; both answered HTTP 200, because that port serves a
 * single-page application and returns its HTML shell for unknown paths. The
 * status code did not distinguish a real endpoint from a catch-all. Only reading
 * the body did.
 */
public class HttpAgentGateway implements AgentGateway {

    private static final Logger LOG = LoggerFactory.getLogger(HttpAgentGateway.class);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private final RestClient sessions;

    public HttpAgentGateway(RestClient sessions) {
        this.sessions = sessions;
    }

    /**
     * Open the checkout, then start a turn in it without waiting for the turn.
     *
     * <p>Three calls, because that is what the gateway offers: the repository
     * shim prepares the branch, a session is created against that directory, and
     * the prompt is submitted asynchronously. Submitting synchronously would
     * leave nothing able to answer whether the turn is still alive.
     */
    @Override
    public String start(StartRequest request) {
        // No checkout step: the agent clones into its own workspace with its own
        // credential. Petri names the directory and nothing else, so it never
        // needs to see the tree - which is what lets it run anywhere, beside any
        // harness, rather than sharing a filesystem with one.
        String directory = request.workspace();

        Map<String, Object> session = sessions.post()
                .uri(builder -> builder.path("/session").queryParam("directory", directory).build())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(Map.of())
                .retrieve()
                .body(JSON_MAP);

        Object sessionId = session == null ? null : session.get("id");
        if (sessionId == null) {
            throw new GatewayException("gateway created no session for " + directory);
        }

        sessions.post()
                .uri("/session/{id}/prompt_async", sessionId.toString())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body(Map.of("parts", List.of(Map.of("type", "text", "text", request.prompt()))))
                .retrieve()
                .toBodilessEntity();

        return sessionId.toString();
    }

    @Override
    public Map<String, SessionSnapshot> observe(List<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }

        // One call covering every session, not one per run: a poll whose cost
        // grows with the board eventually stops running often enough to be
        // liveness at all.
        Map<String, Object> response = sessions.get()
                .uri("/session/status")
                .retrieve()
                .body(JSON_MAP);

        Map<String, SessionSnapshot> snapshots = new HashMap<>();
        for (String id : sessionIds) {
            Object raw = response == null ? null : response.get(id);
            // Absent means not busy, NOT gone. A live gateway was observed
            // returning {} while a session existed: it reports only sessions
            // that are doing something. Treating absence as a lost session
            // would kill every run the moment it stopped producing - including
            // the moment just after it was created.
            snapshots.put(id, raw instanceof Map<?, ?> entry ? parse(id, entry)
                    : new SessionSnapshot(id, SessionState.IDLE, null, null));
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
            sessions.post().uri("/session/{id}/abort", sessionId).retrieve().toBodilessEntity();
        } catch (RuntimeException ex) {
            // A finished session cannot be aborted, and that is the common case
            // when a stop races a completion.
            LOG.debug("Abort of session {} did not apply: {}", sessionId, ex.toString());
        }
    }

    /**
     * The agent's last message, assembled from the session's message parts.
     *
     * <p>Only text parts are kept: tool calls and attachments are how the agent
     * worked, not what it concluded, and whatever reads the conclusion should not
     * have to sift the mechanics out of it.
     */
    @Override
    public String lastMessage(String sessionId) {
        try {
            List<Map<String, Object>> messages = sessions.get()
                    .uri("/session/{id}/message", sessionId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() {});

            if (messages == null || messages.isEmpty()) {
                return "";
            }

            StringBuilder text = new StringBuilder();
            Object parts = messages.getLast().get("parts");
            if (parts instanceof List<?> list) {
                for (Object part : list) {
                    if (part instanceof Map<?, ?> entry && "text".equals(entry.get("type"))) {
                        Object value = entry.get("text");
                        if (value != null) {
                            text.append(value).append(System.lineSeparator());
                        }
                    }
                }
            }
            return text.toString().strip();

        } catch (RuntimeException ex) {
            // Losing the transcript must not lose the run: the gate can still
            // decide on the diff, and the reason is recorded either way.
            LOG.warn("Could not read the last message of session {}: {}", sessionId, ex.toString());
            return "";
        }
    }
}
