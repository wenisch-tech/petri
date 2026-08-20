package tech.wenisch.petri.dto;

import java.util.List;
import tech.wenisch.petri.entity.GateType;

/** One state rendered as a column, with the cards currently sitting in it. */
public record ColumnView(
        Long stateId,
        String name,
        String modelAlias,
        GateType gate,
        boolean terminal,
        List<CardSummary> cards) {

    public int count() {
        return cards.size();
    }

    /** Null model means nobody is driving this column automatically. */
    public String modelLabel() {
        return modelAlias == null ? "manual" : modelAlias;
    }
}
