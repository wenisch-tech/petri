package tech.wenisch.petri.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tech.wenisch.petri.review.OpenAiCompatibleReviewModel;
import tech.wenisch.petri.review.ReviewModel;
import tech.wenisch.petri.review.ReviewProperties;
import tech.wenisch.petri.review.UnavailableReviewModel;

@Configuration
@EnableConfigurationProperties(ReviewProperties.class)
public class ReviewConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewConfig.class);

    @Bean
    ReviewModel reviewModel(ReviewProperties properties) {
        if (!properties.configured()) {
            LOG.info("No reviewing model configured; verdict gates will hold rather than pass");
            return new UnavailableReviewModel();
        }

        LOG.info("Reviewing model '{}' at {}", properties.model(), properties.baseUrl());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        // A review is a model call, so it is slow by nature; this only stops a
        // silent endpoint from wedging the poller that calls it.
        factory.setReadTimeout(properties.timeout());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory);
        if (!properties.apiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }

        return new OpenAiCompatibleReviewModel(builder.build(), properties.model());
    }
}
