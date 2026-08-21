package tech.wenisch.petri.controller;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import tech.wenisch.petri.entity.Board;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.WorkflowState;
import tech.wenisch.petri.repository.BoardRepository;
import tech.wenisch.petri.repository.CardRepository;
import tech.wenisch.petri.repository.WorkflowStateRepository;
import tech.wenisch.petri.service.BoardQueryService;

import java.util.List;

@Controller
public class BoardController {

    private final BoardQueryService query;
    private final BoardRepository boards;
    private final WorkflowStateRepository states;
    private final CardRepository cards;

    public BoardController(BoardQueryService query,
                           BoardRepository boards,
                           WorkflowStateRepository states,
                           CardRepository cards) {
        this.query = query;
        this.boards = boards;
        this.states = states;
        this.cards = cards;
    }

    @GetMapping("/boards/{slug}")
    public String board(@PathVariable String slug, Model model) {
        model.addAttribute("board", query.board(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such board")));
        return "board";
    }

    /**
     * Add a card from the board itself.
     *
     * <p>The API is the right front door for a script; a person wanting to add
     * one task should not have to write a curl command to do it. This goes
     * through the browser chain, so it is a signed-in session and CSRF-protected
     * rather than carrying the API token into a web page.
     */
    @PostMapping("/boards/{slug}/cards")
    public String addCard(@PathVariable String slug,
                          @RequestParam String title,
                          @RequestParam(required = false) String description) {
        if (title == null || title.isBlank()) {
            return "redirect:/boards/" + slug + "?error=title";
        }

        Board board = boards.findBySlug(slug).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such board"));

        List<WorkflowState> pipeline = states.findByBoardOrderByPositionAsc(board);
        if (pipeline.isEmpty()) {
            return "redirect:/boards/" + slug + "?error=nostates";
        }

        Card card = new Card();
        card.setBoard(board);
        card.setState(pipeline.getFirst());
        card.setTitle(title.strip());
        card.setDescription(description == null || description.isBlank()
                ? null : description.strip());
        cards.save(card);

        return "redirect:/cards/" + card.getId();
    }
}
