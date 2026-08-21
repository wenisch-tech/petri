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
 * <p>Two services, deliberately kept apart. The <em>session API</em> creates and
 * observes agent sessions. The <em>repository API</em> is an allowlisted shim
 * over the gateway's own git tooling: it owns the checkout, the credential and
 * the push gate, and Petri only ever asks it questions.
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
    private final RestClient repository;
    private final String workspaceTemplate;

    public HttpAgentGateway(RestClient sessions, RestClient repository, String workspaceTemplate) {
        this.sessions = sessions;
        this.repository = repository;
        this.workspaceTemplate = workspaceTemplate;
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
        Map<String, Object> opened =
                repoCommand("open", List.of(request.repository(), request.branch()));
        if (exitCode(opened) != 0) {
            throw new GatewayException("could not open " + request.repository()
                    + " on " + request.branch() + ": " + output(opened));
        }

        String directory = workspaceFor(request.repository());

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

    /**
     * The gateway lays its workspaces out its own way, so the pattern is
     * configuration rather than a layout guessed at inside this client.
     */
    private String workspaceFor(String repository) {
        String[] parts = repository.split("/", 2);
        String owner = parts.length == 2 ? parts[0] : "";
        String name = parts.length == 2 ? parts[1] : repository;
        return workspaceTemplate
                .replace("{repository}", repository)
                .replace("{owner}", owner)
                .replace("{name}", name);
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
            sessions.post().uri("/session/{id}/abort", sessionId).retrieve().toBodilessEntity();
        } catch (RuntimeException ex) {
            // A finished session cannot be aborted, and that is the common case
            // when a stop races a completion.
            LOG.debug("Abort of session {} did not apply: {}", sessionId, ex.toString());
        }
    }

    @Override
    public String diff(String repository, String branch) {
        return output(repoCommand("diff", List.of()));
    }

    /**
     * The agent's last message, assembled from the session's message parts.
     *
     * <p>Only text parts are kept: tool calls and file attachments are how the
     * agent worked, not what it concluded, and a gate reading the conclusion
     * should not have to sift the mechanics out of it.
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

    @Override
    public GateReport check(String repository, String branch) {
        Map<String, Object> response = repoCommand("check", List.of());
        return new GateReport(exitCode(response) == 0, output(response));
    }

    /**
     * Invoke one allowlisted subcommand on the repository shim.
     *
     * <p>The shim answers {@code exit_code} and {@code output}. A non-zero exit
     * is a refusal carrying real output - a failed gate, a bad branch - not a
     * transport error, so it comes back as data rather than as an exception.
     */
    private Map<String, Object> repoCommand(String command, List<String> args) {
        try {
            Map<String, Object> response = repository.post()
                    .uri("/{command}", command)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(Map.of("args", args))
                    .retrieve()
                    .body(JSON_MAP);
            if (response == null) {
                throw new GatewayException("repository shim returned no result for " + command);
            }
            return response;
        } catch (GatewayException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new GatewayException("repository shim call '" + command + "' failed", ex);
        }
    }

    private int exitCode(Map<String, Object> response) {
        Object value = response.get("exit_code");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String output(Map<String, Object> response) {
        Object value = response.get("output");
        return value == null ? "" : value.toString();
    }
}
