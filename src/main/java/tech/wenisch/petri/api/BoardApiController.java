package tech.wenisch.petri.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.repository.*;

/**
 * The write side: define a pipeline, then put work into it.
 *
 * <p>This is the front door. Everything else in Petri observes or acts on what
 * arrives here.
 */
@RestController
@RequestMapping("/api")
public class BoardApiController {

    private final BoardRepository boards;
    private final WorkflowStateRepository states;
    private final CardRepository cards;

    public BoardApiController(BoardRepository boards,
                              WorkflowStateRepository states,
                              CardRepository cards) {
        this.boards = boards;
        this.states = states;
        this.cards = cards;
    }

    public record NewBoard(
            @NotBlank String slug,
            @NotBlank String name,
            @NotNull Forge forge,
            @NotBlank String repository,
            String defaultBranch) {
    }

    public record NewState(
            @NotBlank String name,
            int position,
            @NotNull GateType gate,
            String modelAlias,
            String promptTemplate,
            String nextOnPass,
            String nextOnFail,
            Integer maxAttempts,
            Boolean terminal,
            Boolean publish) {
    }

    public record NewCard(@NotBlank String title, String description, String state) {
    }

    public record Created(Long id, String url) {
    }

    @PostMapping("/boards")
    ResponseEntity<Created> createBoard(@Valid @RequestBody NewBoard request) {
        boards.findBySlug(request.slug()).ifPresent(existing -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "board already exists");
        });

        Board board = new Board();
        board.setSlug(request.slug());
        board.setName(request.name());
        board.setForge(request.forge());
        board.setRepository(request.repository());
        board.setDefaultBranch(request.defaultBranch() == null || request.defaultBranch().isBlank()
                ? "main" : request.defaultBranch());
        boards.save(board);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new Created(board.getId(), "/boards/" + board.getSlug()));
    }

    /**
     * Replace a board's states in one call.
     *
     * <p>Whole-pipeline rather than one state at a time, because the states
     * reference each other: adding them individually means a window in which
     * {@code nextOnPass} points at something that does not exist yet, and a
     * runner that reads it in that window sends a card nowhere.
     */
    @PutMapping("/boards/{slug}/states")
    ResponseEntity<List<String>> defineStates(@PathVariable String slug,
                                              @Valid @RequestBody List<NewState> requested) {
        Board board = boards.findBySlug(slug).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such board"));

        // Cards point at states, so a state a card is sitting in cannot go. That
        // is the only real constraint: refusing every edit once a board has any
        // card at all made a pipeline permanently frozen the moment it was used,
        // which is exactly when you learn it needs another state.
        Set<String> occupied = cards.findByBoardOrderByIdAsc(board).stream()
                .map(card -> card.getState().getName())
                .collect(Collectors.toSet());
        Set<String> proposed = requested.stream().map(NewState::name).collect(Collectors.toSet());

        List<String> wouldStrand = occupied.stream()
                .filter(name -> !proposed.contains(name))
                .sorted()
                .toList();
        if (!wouldStrand.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
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
        for (NewState request : requested) {
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

        for (NewState request : requested) {
            WorkflowState state = require(board, request.name());
            state.setNextOnPass(resolve(board, request.nextOnPass()));
            state.setNextOnFail(resolve(board, request.nextOnFail()));
            states.save(state);
        }

        return ResponseEntity.ok(states.findByBoardOrderByPositionAsc(board).stream()
                .map(WorkflowState::getName).toList());
    }

    @PostMapping("/boards/{slug}/cards")
    ResponseEntity<Created> createCard(@PathVariable String slug,
                                       @Valid @RequestBody NewCard request) {
        Board board = boards.findBySlug(slug).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such board"));

        List<WorkflowState> pipeline = states.findByBoardOrderByPositionAsc(board);
        if (pipeline.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "the board has no states, so a card has nowhere to start");
        }

        WorkflowState start = request.state() == null || request.state().isBlank()
                ? pipeline.getFirst()
                : require(board, request.state());

        Card card = new Card();
        card.setBoard(board);
        card.setState(start);
        card.setTitle(request.title());
        card.setDescription(request.description());
        cards.save(card);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new Created(card.getId(), "/cards/" + card.getId()));
    }

    private WorkflowState require(Board board, String name) {
        return states.findByBoardAndName(board, name).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no such state: " + name));
    }

    private WorkflowState resolve(Board board, String name) {
        return name == null || name.isBlank() ? null : require(board, name);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
