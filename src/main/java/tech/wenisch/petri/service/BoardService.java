package tech.wenisch.petri.service;

import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.Board;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.repository.BoardRepository;

/**
 * Creating a board, for whoever is asking.
 *
 * <p>Shared by the API and the "new board" page for the same reason {@link
 * PipelineService} is shared by the API and the pipeline editor: the rule that
 * matters - here, that the slug is well-formed and not already taken - must
 * live in one place, or the two front doors drift apart the first time either
 * one changes without the other.
 */
@Service
public class BoardService {

    /**
     * Lowercase letters, digits and hyphens, not starting or ending with one.
     * The slug becomes a URL segment ({@code /boards/{slug}}) the moment the
     * board exists, so a slug that cannot legally be one is refused before it
     * can ever produce a broken link.
     */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    private final BoardRepository boards;

    public BoardService(BoardRepository boards) {
        this.boards = boards;
    }

    public record NewBoard(String slug, String name, Forge forge, String repository, String defaultBranch) {
    }

    /** Why a board could not be created, in terms the caller can act on. */
    public static class BoardException extends RuntimeException {

        public enum Kind {
            /** Another board already has this slug. */
            SLUG_TAKEN,
            /** The slug is not a legal URL segment, or a required field is missing. */
            MALFORMED
        }

        private final transient Kind kind;

        public BoardException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    @Transactional
    public Board create(NewBoard request) {
        if (request.slug() == null || !SLUG.matcher(request.slug()).matches()) {
            throw new BoardException(BoardException.Kind.MALFORMED,
                    "the slug must be lowercase letters, digits and hyphens only, "
                            + "e.g. 'my-project' - it becomes the board's URL");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new BoardException(BoardException.Kind.MALFORMED, "a board needs a name");
        }
        if (request.repository() == null || request.repository().isBlank()) {
            throw new BoardException(BoardException.Kind.MALFORMED, "a board needs a repository");
        }
        if (boards.findBySlug(request.slug()).isPresent()) {
            throw new BoardException(BoardException.Kind.SLUG_TAKEN,
                    "a board with slug '" + request.slug() + "' already exists");
        }

        Board board = new Board();
        board.setSlug(request.slug());
        board.setName(request.name());
        board.setForge(request.forge());
        board.setRepository(request.repository());
        board.setDefaultBranch(request.defaultBranch() == null || request.defaultBranch().isBlank()
                ? "main" : request.defaultBranch());
        boards.save(board);
        return board;
    }
}
