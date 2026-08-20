package tech.wenisch.petri.controller;

import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import tech.wenisch.petri.entity.Board;
import tech.wenisch.petri.service.BoardQueryService;

/** Lists the boards, or goes straight to the board when there is only one. */
@Controller
public class HomeController {

    private final BoardQueryService query;

    public HomeController(BoardQueryService query) {
        this.query = query;
    }

    @GetMapping("/")
    public String index(Model model) {
        List<Board> boards = query.allBoards();
        if (boards.size() == 1) {
            return "redirect:/boards/" + boards.getFirst().getSlug();
        }
        model.addAttribute("boards", boards);
        return "boards";
    }
}
