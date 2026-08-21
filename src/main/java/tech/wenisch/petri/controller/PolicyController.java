package tech.wenisch.petri.controller;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.entity.PetriSettings;
import tech.wenisch.petri.service.PolicySettingsService;

/**
 * The operator-editable policy: concurrency, the run bounds, and the
 * repository gate's protected paths and branch prefix.
 *
 * <p>Every field here can already be set as an environment variable; this page
 * is a second way to set the same thing, one that takes effect without a
 * restart and reverts to the environment default the moment a field is
 * cleared. Nothing here is a secret, which is what keeps this page free of the
 * questions a Connections page would raise about where a credential lives.
 */
@Controller
@RequestMapping("/settings/policy")
public class PolicyController {

    private final PolicySettingsService settings;

    public PolicyController(PolicySettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public String edit(Model model) {
        PetriSettings overlay = settings.current();
        PolicySettingsService.Defaults defaults = settings.environmentDefaults();

        model.addAttribute("overlay", overlay);
        model.addAttribute("defaults", defaults);
        model.addAttribute("defaultProtectedPaths", String.join(", ", defaults.protectedPaths()));
        model.addAttribute("effective", effective());
        return "policy-settings";
    }

    private Effective effective() {
        return new Effective(settings.maxConcurrentRuns(), settings.idleTimeout(),
                settings.maxDuration(), settings.startupGrace(), settings.workspaceRoot(),
                settings.branchPrefix(), String.join(", ", settings.protectedPaths()));
    }

    /** What is actually in force right now, the overlay already merged with the default. */
    public record Effective(int maxConcurrentRuns, Duration idleTimeout, Duration maxDuration,
                            Duration startupGrace, String workspaceRoot, String branchPrefix,
                            String protectedPaths) {
    }

    @PostMapping
    public String save(@RequestParam(required = false) Integer maxConcurrentRuns,
                       @RequestParam(required = false) String idleTimeout,
                       @RequestParam(required = false) String maxDuration,
                       @RequestParam(required = false) String startupGrace,
                       @RequestParam(required = false) String workspaceRoot,
                       @RequestParam(required = false) String branchPrefix,
                       @RequestParam(required = false) String protectedPaths,
                       RedirectAttributes redirect) {
        List<String> problems = validate(idleTimeout, maxDuration, startupGrace);
        if (!problems.isEmpty()) {
            redirect.addFlashAttribute("error", String.join("; ", problems));
            return "redirect:/settings/policy";
        }

        settings.update(maxConcurrentRuns, idleTimeout, maxDuration, startupGrace,
                workspaceRoot, branchPrefix, protectedPaths);
        redirect.addFlashAttribute("saved", true);
        return "redirect:/settings/policy";
    }

    /**
     * Reject an unparsable duration here, in the form, rather than storing it
     * and having a scheduled poller silently fall back to the default ten
     * seconds later with nothing on screen to explain why.
     */
    private List<String> validate(String idleTimeout, String maxDuration, String startupGrace) {
        List<String> problems = new java.util.ArrayList<>();
        checkDuration("idle timeout", idleTimeout, problems);
        checkDuration("max duration", maxDuration, problems);
        checkDuration("startup grace", startupGrace, problems);
        return problems;
    }

    private void checkDuration(String label, String value, List<String> problems) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            Duration.parse(value);
        } catch (DateTimeParseException ex) {
            problems.add("'" + value + "' is not a valid duration for " + label
                    + " - use ISO-8601, e.g. PT15M for 15 minutes");
        }
    }
}
