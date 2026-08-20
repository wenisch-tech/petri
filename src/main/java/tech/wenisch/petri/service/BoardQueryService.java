package tech.wenisch.petri.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.dto.BoardView;
import tech.wenisch.petri.dto.CardDetailView;
import tech.wenisch.petri.dto.CardSummary;
import tech.wenisch.petri.dto.ColumnView;
import tech.wenisch.petri.dto.RunView;
import tech.wenisch.petri.dto.TransitionView;
import tech.wenisch.petri.entity.Board;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.WorkflowState;
import tech.wenisch.petri.repository.AgentRunRepository;
import tech.wenisch.petri.repository.BoardRepository;
import tech.wenisch.petri.repository.CardRepository;
import tech.wenisch.petri.repository.TransitionRepository;
import tech.wenisch.petri.repository.WorkflowStateRepository;

/** Read side of the board. Assembles view models; changes nothing. */
@Service
@Transactional(readOnly = true)
public class BoardQueryService {

    private final BoardRepository boards;
    private final WorkflowStateRepository states;
    private final CardRepository cards;
    private final AgentRunRepository runs;
    private final TransitionRepository transitions;

    public BoardQueryService(BoardRepository boards,
                             WorkflowStateRepository states,
                             CardRepository cards,
                             AgentRunRepository runs,
                             TransitionRepository transitions) {
        this.boards = boards;
        this.states = states;
        this.cards = cards;
        this.runs = runs;
        this.transitions = transitions;
    }

    public List<Board> allBoards() {
        return boards.findAll();
    }

    public Optional<BoardView> board(String slug) {
        return boards.findBySlug(slug).map(this::render);
    }

    private BoardView render(Board board) {
        Instant now = Instant.now();

        // One query for the board's cards and one per card for its latest run,
        // rather than a query per column. Cheap at this size, and the shape does
        // not change when a board grows a state.
        Map<Long, List<Card>> byState = new HashMap<>();
        for (Card card : cards.findByBoardOrderByIdAsc(board)) {
            byState.computeIfAbsent(card.getState().getId(), key -> new ArrayList<>()).add(card);
        }

        List<ColumnView> columns = new ArrayList<>();
        for (WorkflowState state : states.findByBoardOrderByPositionAsc(board)) {
            List<CardSummary> summaries = byState.getOrDefault(state.getId(), List.of()).stream()
                    .map(card -> CardSummary.of(
                            card, runs.findFirstByCardOrderByIdDesc(card).orElse(null), now))
                    .toList();
            columns.add(new ColumnView(
                    state.getId(),
                    state.getName(),
                    state.getModelAlias(),
                    state.getGate(),
                    state.isTerminal(),
                    summaries));
        }

        return new BoardView(
                board.getId(),
                board.getSlug(),
                board.getName(),
                board.getRepository(),
                board.getForge().name(),
                columns);
    }

    public Optional<CardDetailView> card(Long id) {
        return cards.findById(id).map(card -> {
            // Mapped here, inside the transaction. Handing entities to the
            // template instead throws once the session closes.
            List<RunView> cardRuns = runs.findByCardOrderByIdDesc(card).stream()
                    .map(RunView::of).toList();
            List<TransitionView> history = transitions.findByCardOrderByIdAsc(card).stream()
                    .map(TransitionView::of).toList();
            return new CardDetailView(
                    card.getId(),
                    card.getBoard().getSlug(),
                    card.getBoard().getName(),
                    card.getState().getName(),
                    card.getTitle(),
                    card.getDescription(),
                    card.getBranch(),
                    card.getPullRequestUrl(),
                    card.getAttempts(),
                    cardRuns,
                    history);
        });
    }
}
