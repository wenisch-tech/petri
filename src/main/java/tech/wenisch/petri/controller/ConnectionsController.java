package tech.wenisch.petri.controller;

import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.entity.ForgeConnectionSettings;
import tech.wenisch.petri.service.ConnectionSettingsService;
import tech.wenisch.petri.service.ConnectionTestService;

/**
 * Edits what Petri talks to - gateway, forges, reviewing model - with a live
 * reachability check per target.
 *
 * <p>Every field here can already be set as an environment variable at deploy
 * time; this is a second way to set the same thing, one that takes effect on
 * the next call rather than the next restart. One field is the exception: the
 * gateway's password has no form field anywhere on this page, and {@link
 * ConnectionSettingsService#effectiveGateway()} never reads one from the
 * database - it stays only in whatever already holds it, an environment
 * variable or a mounted Secret.
 */
@Controller
@RequestMapping("/settings/connections")
public class ConnectionsController {

    private final ConnectionSettingsService settings;
    private final ConnectionTestService tester;

    public ConnectionsController(ConnectionSettingsService settings, ConnectionTestService tester) {
        this.settings = settings;
        this.tester = tester;
    }

    /** One forge, with its overlay row and the effective value already merged. */
    public record ForgeRow(Forge forge, ForgeConnectionSettings overlay,
                           ConnectionSettingsService.EffectiveForge effective) {
    }

    @GetMapping
    public String view(Model model) {
        model.addAttribute("gatewayOverlay", settings.current());
        model.addAttribute("gatewayEffective", settings.effectiveGateway());

        model.addAttribute("reviewOverlay", settings.current());
        model.addAttribute("reviewEffective", settings.effectiveReview());

        java.util.Set<Forge> configured = settings.configuredForges();
        model.addAttribute("forges", configured.stream()
                .map(forge -> new ForgeRow(forge, settings.currentForge(forge), settings.effectiveForge(forge)))
                .toList());
        model.addAttribute("unconfiguredForgeTypes", List.of(Forge.values()).stream()
                .filter(forge -> !configured.contains(forge))
                .toList());

        return "connections-settings";
    }

    @PostMapping("/gateway")
    public String saveGateway(@RequestParam(required = false) String baseUrl,
                              @RequestParam(required = false) String username,
                              @RequestParam(required = false) Boolean enabled,
                              RedirectAttributes redirect) {
        settings.updateGateway(baseUrl, username, enabled);
        redirect.addFlashAttribute("saved", "gateway");
        return "redirect:/settings/connections";
    }

    @PostMapping("/review")
    public String saveReview(@RequestParam(required = false) String baseUrl,
                             @RequestParam(required = false) String apiKey,
                             @RequestParam(required = false, defaultValue = "false") boolean clearApiKey,
                             @RequestParam(required = false) String model,
                             RedirectAttributes redirect) {
        settings.updateReview(baseUrl, apiKey, clearApiKey, model);
        redirect.addFlashAttribute("saved", "review");
        return "redirect:/settings/connections";
    }

    /**
     * Add a forge not yet configured anywhere.
     *
     * <p>A separate endpoint from {@link #saveForge}, taking the forge type as a
     * request parameter rather than a path variable, because it is chosen from a
     * dropdown at submit time on a page that otherwise has one static form per
     * already-known forge - there is no forge in the URL to put it in yet.
     */
    @PostMapping("/forge")
    public String addForge(@RequestParam Forge forgeType,
                           @RequestParam(required = false) String baseUrl,
                           @RequestParam(required = false) String token,
                           RedirectAttributes redirect) {
        settings.updateForge(forgeType, baseUrl, token, false, null, null, false, null);
        redirect.addFlashAttribute("saved", "forge-" + forgeType);
        return "redirect:/settings/connections";
    }

    @PostMapping("/forge/{forge}")
    public String saveForge(@PathVariable Forge forge,
                            @RequestParam(required = false) String baseUrl,
                            @RequestParam(required = false) String token,
                            @RequestParam(required = false, defaultValue = "false") boolean clearToken,
                            @RequestParam(required = false) Boolean handTokenToAgent,
                            @RequestParam(required = false) String agentToken,
                            @RequestParam(required = false, defaultValue = "false") boolean clearAgentToken,
                            @RequestParam(required = false) String agentUsername,
                            RedirectAttributes redirect) {
        settings.updateForge(forge, baseUrl, token, clearToken,
                handTokenToAgent, agentToken, clearAgentToken, agentUsername);
        redirect.addFlashAttribute("saved", "forge-" + forge);
        return "redirect:/settings/connections";
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
