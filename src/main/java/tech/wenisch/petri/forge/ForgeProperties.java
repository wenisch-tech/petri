package tech.wenisch.petri.forge;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import tech.wenisch.petri.entity.Forge;

/**
 * Where each forge lives and how Petri authenticates to it.
 *
 * <p>Configured per forge type, e.g. {@code petri.forge.forgejo.base-url}. The
 * token here is Petri's own and needs only read access plus the right to open a
 * pull request. It is deliberately <em>not</em> the token the agent pushes with:
 * neither can do the other's job, which is a better position than one credential
 * that can do both.
 */
@ConfigurationProperties(prefix = "petri")
public class ForgeProperties {

    private Map<Forge, Instance> forge = new EnumMap<>(Forge.class);
    private Duration timeout = Duration.ofSeconds(30);

    public Map<Forge, Instance> getForge() {
        return forge;
    }

    public void setForge(Map<Forge, Instance> forge) {
        this.forge = forge;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public static class Instance {
        /** Root of the forge, e.g. {@code https://git.example.com}. */
        private String baseUrl = "";
        /** Petri's token: read, and open a pull request. No push needed. */
        private String token = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }
}
