package tech.wenisch.petri.dto;

import tech.wenisch.petri.entity.GateType;

/** One state as the pipeline editor needs it - everything, nothing hidden. */
public record StateView(
        String name,
        int position,
        GateType gate,
        String modelAlias,
        String promptTemplate,
        String nextOnPass,
        String nextOnFail,
        int maxAttempts,
        boolean terminal,
        boolean publish,
        int cardCount) {
}
