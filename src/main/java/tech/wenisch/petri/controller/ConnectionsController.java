package tech.wenisch.petri.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.forge.ForgeProperties;
import tech.wenisch.petri.gateway.GatewayProperties;
import tech.wenisch.petri.review.ReviewProperties;
import tech.wenisch.petri.service.ConnectionTestService;

/**
 * Read-only view of what Petri is configured to talk to, with a live reachability
 * check for each.
 *
 * <p>Read-only on purpose. These fields carry credentials, and editing them
 * through a form would mean deciding where a token lives once it is no longer
 * only in an environment variable or a mounted Secret - a bigger question than
 * this page answers. What it can safely do instead is turn "is the gateway URL
 * wrong" from a fact discovered at 3am in a startup log into a button that
 * answers in five seconds.
 */
@Controller
@RequestMapping("/settings/connections")
public class ConnectionsController {

    private final GatewayProperties gateway;
    private final ForgeProperties forge;
    private final ReviewProperties review;
    private final ConnectionTestService tester;

    public ConnectionsController(GatewayProperties gateway, ForgeProperties forge,
                                 ReviewProperties review, ConnectionTestService tester) {
        this.gateway = gateway;
        this.forge = forge;
        this.review = review;
        this.tester = tester;
    }

    @GetMapping
    public String view(Model model) {
        model.addAttribute("gateway", gateway);
        model.addAttribute("forges", forge.getForge());
        model.addAttribute("review", review);
        return "connections-settings";
    }

    @PostMapping("/test/gateway")
    public String testGateway(RedirectAttributes redirect) {
        report(redirect, "gateway", tester.testGateway());
        return "redirect:/settings/connections";
    }

    @PostMapping("/test/forge/{forge}")
    public String testForge(@PathVariable Forge forge, RedirectAttributes redirect) {
        // Which forge was tested travels alongside the result under a fixed
        // attribute name, since Thymeleaf reads model attributes by a literal
        // name rather than one computed per forge type.
        redirect.addFlashAttribute("testedForge", forge);
        report(redirect, "forge", tester.testForge(forge));
        return "redirect:/settings/connections";
    }

    @PostMapping("/test/review")
    public String testReview(RedirectAttributes redirect) {
        report(redirect, "review", tester.testReview());
        return "redirect:/settings/connections";
    }

    private void report(RedirectAttributes redirect, String key, ConnectionTestService.Result result) {
        redirect.addFlashAttribute(key + "Ok", result.ok());
        redirect.addFlashAttribute(key + "Detail", result.detail());
    }
}
