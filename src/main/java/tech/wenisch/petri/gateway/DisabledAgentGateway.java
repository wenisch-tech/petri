package tech.wenisch.petri.gateway;

import java.util.List;
import java.util.Map;

/**
 * Stands in when no agent runtime is configured.
 *
 * <p>A fresh install has no agent to drive and the board is still worth looking
 * at. Refusing to start, rather than quietly doing nothing, keeps a card out of
 * a running state that never existed.
 */
public class DisabledAgentGateway implements AgentGateway {

    @Override
    public String start(StartRequest request) {
        throw new GatewayException(
                "no agent runtime is configured; set petri.gateway.base-url");
    }

    @Override
    public Map<String, SessionSnapshot> observe(List<String> sessionIds) {
        return Map.of();
    }

    @Override
    public void abort(String sessionId) {
        // Nothing is running.
    }

    @Override
    public String lastMessage(String sessionId) {
        return "";
    }
}
