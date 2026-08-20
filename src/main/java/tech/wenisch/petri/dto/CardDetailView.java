package tech.wenisch.petri.dto;

import java.util.List;

/**
 * A card with its full history.
 *
 * <p>Everything here is flattened: no entity crosses into the template. With
 * {@code open-in-view=false} the persistence session is closed by the time
 * Thymeleaf renders, so a lazy association reached from a template throws. A
 * test that is itself transactional will not notice, because it holds the
 * session open - only opening the page does.
 *
 * <p>The transition list is the point of this view: a status field says what a
 * card is, while history says how it got there - which model moved it, past
 * which gate, and what the verdict was.
 */
public record CardDetailView(
        Long id,
        String boardSlug,
        String boardName,
        String stateName,
        String title,
        String description,
        String branch,
        String pullRequestUrl,
        int attempts,
        List<RunView> runs,
        List<TransitionView> history) {
}
