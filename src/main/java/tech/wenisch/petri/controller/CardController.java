package tech.wenisch.petri.controller;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import tech.wenisch.petri.service.BoardQueryService;

@Controller
public class CardController {

    private final BoardQueryService query;

    public CardController(BoardQueryService query) {
        this.query = query;
    }

    @GetMapping("/cards/{id}")
    public String card(@PathVariable Long id, Model model) {
        model.addAttribute("card", query.card(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such card")));
        return "card";
    }
}
