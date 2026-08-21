package tech.wenisch.petri.controller;

import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import tech.wenisch.petri.entity.GateType;
import tech.wenisch.petri.repository.BoardRepository;
import tech.wenisch.petri.service.BoardQueryService;
import tech.wenisch.petri.service.PipelineService;

/**
 * The pipeline editor: the states, gates, models and prompts that used to be
 * reachable only through a curl command against {@code PUT /api/boards/{slug}/states}.
 *
 * <p>The page composes the pipeline as JSON in the browser and posts it as one
 * field, rather than one form field per state property. States reference each
 * other by name, are reordered by dragging, and can be added or removed - a
 * fixed set of named form fields cannot represent that, and a dynamic bag of
 * {@code states[0].name}-style fields would need exactly as much JavaScript to
 * keep the indices consistent as building the JSON directly does.
 *
 * <p>Deserializing into {@link PipelineService.StateDefinition} directly, rather
 * than through a form-specific record, keeps this from becoming a second copy of
 * the shape {@link tech.wenisch.petri.api.BoardApiController.NewState} already
 * defines - one drifting out of sync with the other is exactly how a field stops
 * being editable from one of the two front doors without anyone deciding that.
 */
@Controller
public class PipelineController {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineController.class);

    private final BoardQueryService query;
    private final BoardRepository boards;
    private final PipelineService pipelines;
    private final ObjectMapper mapper;

    public PipelineController(BoardQueryService query,
                              BoardRepository boards,
                              PipelineService pipelines,
                              ObjectMapper mapper) {
        this.query = query;
        this.boards = boards;
        this.pipelines = pipelines;
        this.mapper = mapper;
    }

    @GetMapping("/boards/{slug}/pipeline")
    public String edit(@PathVariable String slug, Model model) {
        Board board = boards.findBySlug(slug).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such board"));

        model.addAttribute("board", board);
        model.addAttribute("states", query.pipeline(slug).orElseThrow());
        model.addAttribute("gates", GateType.values());
        return "pipeline";
    }

    @PostMapping("/boards/{slug}/pipeline")
    public String save(@PathVariable String slug,
                       @RequestParam String pipelineJson,
                       RedirectAttributes redirect) {
        Board board = boards.findBySlug(slug).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such board"));

        List<PipelineService.StateDefinition> requested;
        try {
            requested = mapper.readValue(pipelineJson,
                    new TypeReference<List<PipelineService.StateDefinition>>() { });
        } catch (JacksonException ex) {
            // The browser built this JSON itself; a parse failure here means a
            // bug in the page's own script, not a bad user edit. Worth logging
            // rather than presenting as if the pipeline itself were rejected.
            LOG.warn("Pipeline JSON from the editor for board {} did not parse: {}",
                    slug, ex.getMessage());
            redirect.addFlashAttribute("error", "the editor sent something Petri could not read");
            return "redirect:/boards/" + slug + "/pipeline";
        }

        try {
            pipelines.replace(board, requested);
        } catch (PipelineService.PipelineException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
            return "redirect:/boards/" + slug + "/pipeline";
        }

        return "redirect:/boards/" + slug;
    }
}
