package tech.wenisch.petri.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.Board;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.GateType;
import tech.wenisch.petri.entity.WorkflowState;
import tech.wenisch.petri.repository.CardRepository;
import tech.wenisch.petri.repository.WorkflowStateRepository;

/**
 * Defining a board's pipeline, for whoever is asking.
 *
 * <p>Lives here rather than in a controller because two front doors need it: a
 * script calling the API, and a person using the editor. The unlink-delete-link
 * dance below is fiddly enough that a second copy of it would eventually differ
 * from the first, and the difference would show up as a card going nowhere.
 */
@Service
public class PipelineService {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineService.class);

    /**
     * One state as the caller wants it to end up.
     *
     * <p>Links are names rather than ids: a pipeline is written as a whole, and
     * a state may point at one that this same call is about to create.
     */
    public record StateDefinition(String name, int position, GateType gate, String modelAlias,
                                  String promptTemplate, String nextOnPass, String nextOnFail,
                                  Integer maxAttempts, Boolean terminal, Boolean publish) {
    }

    /** Why a pipeline was refused, in terms the caller can act on. */
    public static class PipelineException extends RuntimeException {

        public enum Kind {
            /** Cards are sitting in a state the new pipeline does not have. */
            STRANDED_CARDS,
            /** The pipeline is malformed: no states, duplicates, unknown links. */
            MALFORMED
        }

        private final transient Kind kind;

        public PipelineException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    private final WorkflowStateRepository states;
    private final CardRepository cards;

    public PipelineService(WorkflowStateRepository states, CardRepository cards) {
        this.states = states;
        this.cards = cards;
    }

    /**
     * Replace a board's states in one go.
     *
     * <p>Whole-pipeline rather than one state at a time, because the states
     * reference each other: applying them individually means a window in which
     * {@code nextOnPass} points at something that does not exist yet, and a
     * runner reading it in that window sends a card nowhere.
     *
     * @return the resulting state names, in order
     */
    @Transactional
    public List<String> replace(Board board, List<StateDefinition> requested) {
        validate(requested);

        // Cards point at states, so a state a card is sitting in cannot go. That
        // is the only real constraint: refusing every edit once a board has any
        // card at all made a pipeline permanently frozen the moment it was used,
        // which is exactly when you learn it needs another state.
        Set<String> occupied = cards.findByBoardOrderByIdAsc(board).stream()
                .map(card -> card.getState().getName())
                .collect(Collectors.toSet());
        Set<String> proposed = requested.stream()
                .map(StateDefinition::name).collect(Collectors.toSet());

        List<String> wouldStrand = occupied.stream()
                .filter(name -> !proposed.contains(name))
                .sorted()
                .toList();
        if (!wouldStrand.isEmpty()) {
            throw new PipelineException(PipelineException.Kind.STRANDED_CARDS,
                    "cards are sitting in " + String.join(", ", wouldStrand)
                            + "; move them before removing those states");
        }

        Map<String, WorkflowState> byName = states.findByBoardOrderByPositionAsc(board).stream()
                .collect(Collectors.toMap(WorkflowState::getName, state -> state));

        // Unlink first: a state cannot be deleted while another still points at
        // it, and one that survives must not keep a link to one that does not.
        byName.values().forEach(state -> {
            state.setNextOnPass(null);
            state.setNextOnFail(null);
        });
        states.saveAll(byName.values());

        List<WorkflowState> removed = byName.entrySet().stream()
                .filter(entry -> !proposed.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        states.deleteAll(removed);
        removed.forEach(state -> byName.remove(state.getName()));

        // Two passes: settle every state, then link them. A single pass cannot
        // resolve a forward reference to a state it has not made yet.
        //
        // Existing states are updated in place rather than replaced, so the
        // cards, runs and history pointing at them survive the edit.
        for (StateDefinition request : requested) {
            WorkflowState state = byName.getOrDefault(request.name(), new WorkflowState());
            state.setBoard(board);
            state.setName(request.name());
            state.setPosition(request.position());
            state.setGate(request.gate());
            state.setModelAlias(blankToNull(request.modelAlias()));
            state.setPromptTemplate(blankToNull(request.promptTemplate()));
            state.setTerminal(Boolean.TRUE.equals(request.terminal()));
            state.setPublish(Boolean.TRUE.equals(request.publish()));
            if (request.maxAttempts() != null) {
                state.setMaxAttempts(request.maxAttempts());
            }
            states.save(state);
        }

        for (StateDefinition request : requested) {
            WorkflowState state = require(board, request.name());
            state.setNextOnPass(resolve(board, request.nextOnPass()));
            state.setNextOnFail(resolve(board, request.nextOnFail()));
            states.save(state);
        }

        List<String> result = states.findByBoardOrderByPositionAsc(board).stream()
                .map(WorkflowState::getName).toList();
        LOG.info("Board {} pipeline is now {}", board.getSlug(), result);
        return result;
    }

    /**
     * Refuse a pipeline that cannot mean what it says.
     *
     * <p>Duplicates matter more than they look: the states are keyed by name, so
     * two rows called the same thing quietly collapse into one and the second
     * one's settings win. Rejecting is the only answer that does not silently
     * discard something the author wrote.
     */
    private void validate(List<StateDefinition> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new PipelineException(PipelineException.Kind.MALFORMED,
                    "a pipeline needs at least one state, or cards have nowhere to be");
        }

        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (StateDefinition state : requested) {
            if (state.name() == null || state.name().isBlank()) {
                throw new PipelineException(PipelineException.Kind.MALFORMED,
                        "every state needs a name");
            }
            if (state.gate() == null) {
                throw new PipelineException(PipelineException.Kind.MALFORMED,
                        "state '" + state.name() + "' has no gate");
            }
            if (!seen.add(state.name())) {
                duplicates.add(state.name());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new PipelineException(PipelineException.Kind.MALFORMED,
                    "more than one state called " + String.join(", ", duplicates));
        }

        for (StateDefinition state : requested) {
            requireProposed(seen, state.nextOnPass(), state.name(), "pass");
            requireProposed(seen, state.nextOnFail(), state.name(), "fail");
        }
    }

    private void requireProposed(Set<String> proposed, String target, String from, String edge) {
        if (target != null && !target.isBlank() && !proposed.contains(target)) {
            throw new PipelineException(PipelineException.Kind.MALFORMED,
                    "state '" + from + "' sends " + edge + " to '" + target
                            + "', which is not in this pipeline");
        }
    }

    private WorkflowState require(Board board, String name) {
        return states.findByBoardAndName(board, name).orElseThrow(
                () -> new PipelineException(PipelineException.Kind.MALFORMED,
                        "no such state: " + name));
    }

    private WorkflowState resolve(Board board, String name) {
        return name == null || name.isBlank() ? null : require(board, name);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Where a card would land, for a caller that only wants to look. */
    public List<Card> cardsOn(Board board) {
        return cards.findByBoardOrderByIdAsc(board);
    }
}
