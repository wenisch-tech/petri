package tech.wenisch.petri.review;

/** The reviewing model could not be reached, or answered unusably. */
public class ReviewException extends RuntimeException {

    public ReviewException(String message) {
        super(message);
    }

    public ReviewException(String message, Throwable cause) {
        super(message, cause);
    }
}
