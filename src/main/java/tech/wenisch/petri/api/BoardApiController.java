package tech.wenisch.petri.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.repository.*;
import tech.wenisch.petri.service.PipelineService;

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
    private final PipelineService pipelines;

    public BoardApiController(BoardRepository boards,
                              WorkflowStateRepository states,
                              CardRepository cards,
                              PipelineService pipelines) {
        this.boards = boards;
        this.states = states;
        this.cards = cards;
        this.pipelines = pipelines;
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

        PipelineService.StateDefinition toDefinition() {
            return new PipelineService.StateDefinition(name, position, gate, modelAlias,
                    promptTemplate, nextOnPass, nextOnFail, maxAttempts, terminal, publish);
        }
    }

    public record NewCard(@NotBlank String title, String description, String state) {
    }

    public record Created(Long id, String url) {
    }

    public record UpdateBoard(
            @NotBlank String name,
            @NotNull Forge forge,
            @NotBlank String repository,
            String defaultBranch,
            Boolean enabled) {
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
     * Update a board's own settings - name, forge, repository, default branch,
     * whether the runner may pick up work here at all.
     *
     * <p>The slug is not among them. It is the board's URL and its identity to
     * anything that has bookmarked or scripted against {@code /boards/{slug}};
     * renaming it out from under those references is a bigger decision than
     * this endpoint should make silently.
     */
    @PutMapping("/boards/{slug}")
    ResponseEntity<Void> updateBoard(@PathVariable String slug,
                                     @Valid @RequestBody UpdateBoard request) {
        Board board = boards.findBySlug(slug).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such board"));

        board.setName(request.name());
        board.setForge(request.forge());
        board.setRepository(request.repository());
        board.setDefaultBranch(request.defaultBranch() == null || request.defaultBranch().isBlank()
                ? "main" : request.defaultBranch());
        if (request.enabled() != null) {
            board.setEnabled(request.enabled());
        }
        boards.save(board);

        return ResponseEntity.ok().build();
    }

    /**
     * Replace a board's states in one call.
     *
     * <p>The rule that matters - a state a card is sitting in cannot go - and the
     * unlink-delete-link dance that applies it both live in {@link PipelineService},
     * shared with the board editor so the two front doors cannot drift apart.
     */
    @PutMapping("/boards/{slug}/states")
    ResponseEntity<List<String>> defineStates(@PathVariable String slug,
                                              @Valid @RequestBody List<NewState> requested) {
        Board board = boards.findBySlug(slug).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such board"));

        try {
            List<String> result = pipelines.replace(board,
                    requested.stream().map(NewState::toDefinition).toList());
            return ResponseEntity.ok(result);
        } catch (PipelineService.PipelineException ex) {
            HttpStatus status = ex.kind() == PipelineService.PipelineException.Kind.STRANDED_CARDS
                    ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
            throw new ResponseStatusException(status, ex.getMessage());
        }
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
}
