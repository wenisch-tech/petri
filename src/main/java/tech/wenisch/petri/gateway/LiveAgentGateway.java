package tech.wenisch.petri.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tech.wenisch.petri.service.ConnectionSettingsService;

/**
 * The {@link AgentGateway} bean, resolved fresh on every call from {@link
 * ConnectionSettingsService} rather than built once at startup.
 *
 * <p>This is what makes the Connections screen's gateway fields take effect
 * without a restart: there is no cached client to go stale. Building a {@link
 * RestClient} is cheap - it wraps a request factory, nothing that warms up or
 * holds a pool - so there is nothing worth caching here; the alternative would
 * be a cache with an invalidation rule to get right, for a saving that would
 * not be measurable.
 *
 * <p>The password is the one field this never reads from the overlay. {@link
 * ConnectionSettingsService#effectiveGateway()} always answers with the
 * environment's password, so there is no path through this class that could
 * read one from the database even if a row somehow had it.
 */
@Component
public class LiveAgentGateway implements AgentGateway {

    private final ConnectionSettingsService settings;
    private final DisabledAgentGateway disabled = new DisabledAgentGateway();

    public LiveAgentGateway(ConnectionSettingsService settings) {
        this.settings = settings;
    }

    @Override
    public String start(StartRequest request) {
        return resolve().start(request);
    }

    @Override
    public Map<String, SessionSnapshot> observe(List<String> sessionIds) {
        return resolve().observe(sessionIds);
    }

    @Override
    public void abort(String sessionId) {
        resolve().abort(sessionId);
    }

    @Override
    public String lastMessage(String sessionId) {
        return resolve().lastMessage(sessionId);
    }

    private AgentGateway resolve() {
        ConnectionSettingsService.EffectiveGateway effective = settings.effectiveGateway();
        if (!effective.enabled() || !effective.configured()) {
            return disabled;
        }
        return new HttpAgentGateway(client(effective));
    }

    private RestClient client(ConnectionSettingsService.EffectiveGateway effective) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(effective.requestTimeout());
        factory.setReadTimeout(effective.requestTimeout());

        String credentials = effective.username() + ":" + effective.password();
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        return RestClient.builder()
                .baseUrl(effective.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basic)
                .build();
    }
}
