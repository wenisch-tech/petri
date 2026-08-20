package tech.wenisch.petri.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the board. Until the state machine exists this is a placeholder page. */
@Controller
public class HomeController {

    private final String applicationName;

    public HomeController(
            @org.springframework.beans.factory.annotation.Value("${spring.application.name}")
            String applicationName) {
        this.applicationName = applicationName;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("applicationName", applicationName);
        return "index";
    }
}
