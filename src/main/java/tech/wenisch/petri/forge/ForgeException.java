package tech.wenisch.petri.forge;

/** The forge could not be reached, or answered in a way Petri cannot use. */
public class ForgeException extends RuntimeException {

    public ForgeException(String message) {
        super(message);
    }

    public ForgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
