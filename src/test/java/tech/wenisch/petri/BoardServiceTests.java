package tech.wenisch.petri;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.Board;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.service.BoardService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The slug rule shared by the API and the "new board" page. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BoardServiceTests {

    @Autowired private BoardService service;

    private BoardService.NewBoard request(String slug) {
        return new BoardService.NewBoard(slug, "A Board", Forge.FORGEJO, "example/repo", null);
    }

    @Test
    void aWellFormedSlugIsAccepted() {
        Board board = service.create(request("well-formed-123"));

        assertThat(board.getId()).isNotNull();
        assertThat(board.getDefaultBranch()).isEqualTo("main");
    }

    @Test
    void aSlugWithSpacesIsRefused() {
        assertThatThrownBy(() -> service.create(request("not a slug")))
                .isInstanceOf(BoardService.BoardException.class)
                .satisfies(ex -> assertThat(((BoardService.BoardException) ex).kind())
                        .isEqualTo(BoardService.BoardException.Kind.MALFORMED));
    }

    @Test
    void aSlugWithUppercaseIsRefused() {
        assertThatThrownBy(() -> service.create(request("NotLowercase")))
                .isInstanceOf(BoardService.BoardException.class);
    }

    @Test
    void aLeadingOrTrailingHyphenIsRefused() {
        assertThatThrownBy(() -> service.create(request("-leading")))
                .isInstanceOf(BoardService.BoardException.class);
        assertThatThrownBy(() -> service.create(request("trailing-")))
                .isInstanceOf(BoardService.BoardException.class);
    }

    @Test
    void aDuplicateSlugIsRefused() {
        service.create(request("duplicate"));

        assertThatThrownBy(() -> service.create(request("duplicate")))
                .isInstanceOf(BoardService.BoardException.class)
                .satisfies(ex -> assertThat(((BoardService.BoardException) ex).kind())
                        .isEqualTo(BoardService.BoardException.Kind.SLUG_TAKEN));
    }

    @Test
    void aBlankDefaultBranchFallsBackToMain() {
        Board board = service.create(
                new BoardService.NewBoard("blank-branch", "A Board", Forge.FORGEJO, "example/repo", "  "));

        assertThat(board.getDefaultBranch()).isEqualTo("main");
    }
}
