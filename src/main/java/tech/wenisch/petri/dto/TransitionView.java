package tech.wenisch.petri.dto;

import java.time.Instant;
import tech.wenisch.petri.entity.Transition;

/** One row of a card's history, flattened inside the transaction. */
public record TransitionView(
        String fromState,
        String toState,
        String actor,
        String verdict,
        Instant at) {

    public static TransitionView of(Transition transition) {
        return new TransitionView(
                transition.getFromState() == null ? null : transition.getFromState().getName(),
                transition.getToState().getName(),
                transition.getActor(),
                transition.getVerdict(),
                transition.getCreatedAt());
    }
}
