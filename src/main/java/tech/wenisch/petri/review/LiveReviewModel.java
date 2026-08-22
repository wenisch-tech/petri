package tech.wenisch.petri.review;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tech.wenisch.petri.service.ConnectionSettingsService;

/**
 * The {@link ReviewModel} bean, resolved fresh from {@link
 * ConnectionSettingsService} on every review rather than built once at
 * startup - the same reasoning as {@link tech.wenisch.petri.gateway.LiveAgentGateway}.
 */
@Component
public class LiveReviewModel implements ReviewModel {

    private final ConnectionSettingsService settings;
    private final UnavailableReviewModel unavailable = new UnavailableReviewModel();

    public LiveReviewModel(ConnectionSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public String review(String system, String prompt) {
        return resolve().review(system, prompt);
    }

    private ReviewModel resolve() {
        ConnectionSettingsService.EffectiveReview effective = settings.effectiveReview();
        if (!effective.configured()) {
            return unavailable;
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(effective.timeout());
        factory.setReadTimeout(effective.timeout());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(effective.baseUrl())
                .requestFactory(factory);
        if (!effective.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + effective.apiKey());
        }

        return new OpenAiCompatibleReviewModel(builder.build(), effective.model());
    }
}
