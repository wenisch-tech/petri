package tech.wenisch.petri.review;

import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Asks an OpenAI-compatible chat endpoint for a verdict.
 *
 * <p>Written against the wire format rather than a vendor SDK: the endpoint in
 * front of these models is usually a proxy, the shape is small and stable, and a
 * client this size is easier to keep honest than a dependency whose surface
 * moves.
 */
public class OpenAiCompatibleReviewModel implements ReviewModel {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final String model;

    public OpenAiCompatibleReviewModel(RestClient client, String model) {
        this.client = client;
        this.model = model;
    }

    @Override
    public String review(String system, String prompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                // Deterministic as the endpoint allows. A review that returns a
                // different verdict on the same diff is not a gate.
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", prompt)));

        Map<String, Object> response;
        try {
            response = client.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(body)
                    .retrieve()
                    .body(JSON_MAP);
        } catch (RuntimeException ex) {
            throw new ReviewException("review call failed", ex);
        }

        if (response == null) {
            throw new ReviewException("reviewing model returned no body");
        }

        Object choices = response.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            throw new ReviewException("reviewing model returned no choices");
        }
        if (!(list.getFirst() instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)) {
            throw new ReviewException("reviewing model returned an unusable choice");
        }

        Object content = message.get("content");
        if (content == null || content.toString().isBlank()) {
            throw new ReviewException("reviewing model returned empty content");
        }
        return content.toString();
    }
}
