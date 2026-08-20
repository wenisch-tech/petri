package tech.wenisch.petri.dto;

import java.util.List;

/** A whole board: its states in order, each holding its cards. */
public record BoardView(
        Long id,
        String slug,
        String name,
        String repository,
        String forge,
        List<ColumnView> columns) {

    public int cardCount() {
        return columns.stream().mapToInt(ColumnView::count).sum();
    }
}
