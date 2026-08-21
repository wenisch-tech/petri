package tech.wenisch.petri.gateway;

import java.util.List;
import java.util.Map;

/**
 * Stands in when no gateway is configured.
 *
 * <p>A fresh install has no agent to drive, and the board is still worth looking
 * at. Refusing to start rather than silently doing nothing means a card cannot
 * sit in a running state that never existed.
 */
public class DisabledAgentGateway implements AgentGateway {

    @Override
    public String start(StartRequest request) {
        throw new GatewayException(
                "no agent gateway is configured; set petri.gateway.base-url and "
                        + "petri.gateway.enabled=true");
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
    public String diff(String repository, String branch) {
        return "";
    }

    @Override
    public String lastMessage(String sessionId) {
        return "";
    }

    @Override
    public GateReport check(String repository, String branch) {
        // Refuse rather than report a pass nobody performed: a gate that says
        // yes without checking is worse than no gate at all.
        return new GateReport(false, "no agent gateway is configured, so nothing was checked");
    }
}
