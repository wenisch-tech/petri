package tech.wenisch.petri.entity;

/** Lifecycle of a single execution of a state's action against a card. */
public enum RunStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    ABORTED;

    public boolean isFinished() {
        return this == SUCCEEDED || this == FAILED || this == ABORTED;
    }
}
