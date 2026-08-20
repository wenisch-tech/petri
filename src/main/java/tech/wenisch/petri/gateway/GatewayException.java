package tech.wenisch.petri.gateway;

/** The gateway could not be reached, or answered in a way Petri cannot use. */
public class GatewayException extends RuntimeException {

    public GatewayException(String message) {
        super(message);
    }

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
