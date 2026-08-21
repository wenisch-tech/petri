package tech.wenisch.petri.controller;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.entity.Board;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.repository.BoardRepository;

/**
 * A board's own settings: name, forge, repository, default branch, whether the
 * runner may pick up work here at all.
 *
 * <p>The slug is deliberately not editable here. It is the board's URL and its
 * identity to anything that has bookmarked or scripted against
 * {@code /boards/{slug}}; changing it out from under those references is a
 * bigger decision than a settings form should make on someone's behalf.
 */
@Controller
public class BoardSettingsController {

    private final BoardRepository boards;

    public BoardSettingsController(BoardRepository boards) {
        this.boards = boards;
    }

    @GetMapping("/boards/{slug}/settings")
    public String edit(@PathVariable String slug, Model model) {
        Board board = find(slug);
        model.addAttribute("board", board);
        model.addAttribute("forges", Forge.values());
        return "board-settings";
    }

    @PostMapping("/boards/{slug}/settings")
    public String save(@PathVariable String slug,
                       @RequestParam @NotBlank String name,
                       @RequestParam Forge forge,
                       @RequestParam @NotBlank String repository,
                       @RequestParam(required = false) String defaultBranch,
                       @RequestParam(required = false) Boolean enabled,
                       RedirectAttributes redirect) {
        Board board = find(slug);

        board.setName(name);
        board.setForge(forge);
        board.setRepository(repository);
        board.setDefaultBranch(defaultBranch == null || defaultBranch.isBlank()
                ? "main" : defaultBranch);
        // An unchecked HTML checkbox sends no parameter at all, not "false" - so
        // absence here means "off", not "leave as it was".
        board.setEnabled(Boolean.TRUE.equals(enabled));
        boards.save(board);

        redirect.addFlashAttribute("saved", true);
        return "redirect:/boards/" + slug;
    }

    private Board find(String slug) {
        return boards.findBySlug(slug).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such board"));
    }
}
