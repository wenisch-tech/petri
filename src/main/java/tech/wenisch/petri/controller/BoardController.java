package tech.wenisch.petri.controller;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import tech.wenisch.petri.service.BoardQueryService;

@Controller
public class BoardController {

    private final BoardQueryService query;

    public BoardController(BoardQueryService query) {
        this.query = query;
    }

    @GetMapping("/boards/{slug}")
    public String board(@PathVariable String slug, Model model) {
        model.addAttribute("board", query.board(slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such board")));
        return "board";
    }
}
