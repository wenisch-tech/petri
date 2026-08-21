package tech.wenisch.petri.review;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where to reach the reviewing model.
 *
 * <p>An OpenAI-compatible endpoint, which is what a local proxy in front of
 * self-hosted models exposes as well as the hosted services, so the same
 * settings cover both.
 *
 * @param baseUrl endpoint root, e.g. {@code http://litellm:4000/v1}
 * @param apiKey  bearer token
 * @param model   alias to ask; deliberately separate from the model that writes
 *                the code, since a reviewer marking its own work is not a review
 * @param timeout bound on a single review call
 */
@ConfigurationProperties(prefix = "petri.review")
public record ReviewProperties(String baseUrl, String apiKey, String model, Duration timeout) {

    public ReviewProperties {
        baseUrl = baseUrl == null ? "" : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        model = model == null || model.isBlank() ? "chatgpt" : model;
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
    }

    public boolean configured() {
        return !baseUrl.isBlank();
    }
}
