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

    /**
     * Per-request timeout for forge calls.
     *
     * <p>Named {@code petri.forge-timeout} rather than {@code petri.forge.timeout}
     * because {@code petri.forge} is a map keyed by forge type: a sibling key
     * under it binds as the name of a forge that does not exist, and the whole
     * application fails to start.
     */
    private Duration forgeTimeout = Duration.ofSeconds(30);

    public Map<Forge, Instance> getForge() {
        return forge;
    }

    public void setForge(Map<Forge, Instance> forge) {
        this.forge = forge;
    }

    public Duration getForgeTimeout() {
        return forgeTimeout;
    }

    public void setForgeTimeout(Duration forgeTimeout) {
        this.forgeTimeout = forgeTimeout;
    }

    public static class Instance {
        /** Root of the forge, e.g. {@code https://git.example.com}. */
        private String baseUrl = "";
        /** Petri's token: read, and open a pull request. No push needed. */
        private String token = "";

        /**
         * Hand a credential to the agent so it can clone and push by itself.
         *
         * <p>Off by default, and a switch rather than an assumption, because it
         * decides who holds the push right. Off, the agent needs its own
         * credential, arranged wherever it runs - Petri never sees it, and the
         * blast radius of Petri being compromised is whatever its own read
         * token can reach. On, Petri hands over a credential in the clone URL,
         * which is convenient and strictly worse: it reaches the agent's disk,
         * its logs, and anything that can read either.
         */
        private boolean handTokenToAgent = false;

        /**
         * The token to hand over, when the switch is on.
         *
         * <p>Separate from {@link #token} so the two rights can be separated:
         * this one pushes, Petri's own does not. Falls back to Petri's token
         * when unset, which is the convenient case and the less safe one.
         */
        private String agentToken = "";

        /** Username paired with {@link #agentToken} in the clone URL. */
        private String agentUsername = "petri";

        public boolean isHandTokenToAgent() {
            return handTokenToAgent;
        }

        public void setHandTokenToAgent(boolean handTokenToAgent) {
            this.handTokenToAgent = handTokenToAgent;
        }

        public String getAgentToken() {
            return agentToken;
        }

        public void setAgentToken(String agentToken) {
            this.agentToken = agentToken;
        }

        public String getAgentUsername() {
            return agentUsername;
        }

        public void setAgentUsername(String agentUsername) {
            this.agentUsername = agentUsername;
        }

        /** The credential the agent should be given, or empty if it gets none. */
        public String credentialForAgent() {
            if (!handTokenToAgent) {
                return "";
            }
            return agentToken == null || agentToken.isBlank()
                    ? (token == null ? "" : token) : agentToken;
        }

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
