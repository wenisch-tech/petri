package tech.wenisch.petri.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.entity.Board;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.service.BoardService;

/**
 * Creating a board from the browser.
 *
 * <p>The rule that matters - a well-formed, unused slug - lives in {@link
 * BoardService}, shared with the API so the two front doors cannot drift
 * apart.
 */
@Controller
public class NewBoardController {

    private final BoardService boardService;

    public NewBoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    @GetMapping("/boards/new")
    public String form(Model model) {
        model.addAttribute("forges", Forge.values());
        return "new-board";
    }

    @PostMapping("/boards/new")
    public String create(@RequestParam String slug,
                         @RequestParam String name,
                         @RequestParam Forge forge,
                         @RequestParam String repository,
                         @RequestParam(required = false) String defaultBranch,
                         RedirectAttributes redirect) {
        try {
            Board board = boardService.create(
                    new BoardService.NewBoard(slug, name, forge, repository, defaultBranch));
            // Straight to the pipeline editor: a board with no states yet has
            // nothing else useful to do, and defining one is the very next
            // step regardless of how the board got created.
            return "redirect:/boards/" + board.getSlug() + "/pipeline";
        } catch (BoardService.BoardException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            redirect.addFlashAttribute("slug", slug);
            redirect.addFlashAttribute("name", name);
            redirect.addFlashAttribute("forge", forge);
            redirect.addFlashAttribute("repository", repository);
            redirect.addFlashAttribute("defaultBranch", defaultBranch);
            return "redirect:/boards/new";
        }
    }
}
