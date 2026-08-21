package tech.wenisch.petri.service;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.PetriSettings;
import tech.wenisch.petri.gateway.GatewayProperties;
import tech.wenisch.petri.repository.PetriSettingsRepository;

/**
 * The operator-editable policy, merged from a database overlay and environment
 * defaults.
 *
 * <p>Every value here can be set two ways: once at deploy time as an
 * environment variable or property, and again - optionally - through the
 * Policy screen, which takes effect immediately without a restart. The row is
 * read fresh on every call rather than cached, because a Policy edit must be
 * visible to the very next poll cycle; a single-row lookup by primary key is
 * cheap enough that this costs nothing worth avoiding.
 *
 * <p>The environment default is never discarded, only shadowed - clearing a
 * field back to blank in the UI returns it to whatever the deployment already
 * specifies, which is what lets a GitOps-managed default coexist with an
 * operator's temporary override rather than one silently fighting the other.
 */
@Service
public class PolicySettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(PolicySettingsService.class);
    private static final Long ROW_ID = 1L;

    private final PetriSettingsRepository repository;
    private final GatewayProperties gatewayDefaults;
    private final int defaultMaxConcurrentRuns;
    private final String defaultWorkspaceRoot;
    private final List<String> defaultProtectedPaths;
    private final String defaultBranchPrefix;

    public PolicySettingsService(
            PetriSettingsRepository repository,
            GatewayProperties gatewayDefaults,
            @Value("${petri.max-concurrent-runs:1}") int defaultMaxConcurrentRuns,
            @Value("${petri.workspace-root:/workspaces/petri}") String defaultWorkspaceRoot,
            @Value("${petri.gate.protected-paths:.github/**,.forgejo/**,Dockerfile,**/Dockerfile,*.tfstate,**/*.tfstate}")
            List<String> defaultProtectedPaths,
            @Value("${petri.gate.branch-prefix:petri/}") String defaultBranchPrefix) {
        this.repository = repository;
        this.gatewayDefaults = gatewayDefaults;
        this.defaultMaxConcurrentRuns = Math.max(1, defaultMaxConcurrentRuns);
        this.defaultWorkspaceRoot = defaultWorkspaceRoot;
        this.defaultProtectedPaths = defaultProtectedPaths;
        this.defaultBranchPrefix = defaultBranchPrefix;
    }

    private PetriSettings row() {
        return repository.findById(ROW_ID).orElseGet(PetriSettings::new);
    }

    public int maxConcurrentRuns() {
        Integer override = row().getMaxConcurrentRuns();
        return override != null && override > 0 ? override : defaultMaxConcurrentRuns;
    }

    public Duration idleTimeout() {
        return parse(row().getIdleTimeout(), gatewayDefaults.idleTimeout());
    }

    public Duration maxDuration() {
        return parse(row().getMaxDuration(), gatewayDefaults.maxDuration());
    }

    public Duration startupGrace() {
        return parse(row().getStartupGrace(), gatewayDefaults.startupGrace());
    }

    public String workspaceRoot() {
        String override = row().getWorkspaceRoot();
        return override == null || override.isBlank() ? defaultWorkspaceRoot : override;
    }

    public String branchPrefix() {
        String override = row().getBranchPrefix();
        return override == null || override.isBlank() ? defaultBranchPrefix : override;
    }

    public List<String> protectedPaths() {
        String override = row().getProtectedPaths();
        if (override == null || override.isBlank()) {
            return defaultProtectedPaths;
        }
        return Arrays.stream(override.split(","))
                .map(String::trim)
                .filter(path -> !path.isBlank())
                .toList();
    }

    /** What the environment alone would give, for rendering the Policy form's placeholders. */
    public record Defaults(int maxConcurrentRuns, Duration idleTimeout, Duration maxDuration,
                           Duration startupGrace, String workspaceRoot, String branchPrefix,
                           List<String> protectedPaths) {
    }

    public Defaults environmentDefaults() {
        return new Defaults(defaultMaxConcurrentRuns, gatewayDefaults.idleTimeout(),
                gatewayDefaults.maxDuration(), gatewayDefaults.startupGrace(),
                defaultWorkspaceRoot, defaultBranchPrefix, defaultProtectedPaths);
    }

    /** The overlay row itself, for rendering what is currently overridden. */
    @Transactional(readOnly = true)
    public PetriSettings current() {
        return row();
    }

    /**
     * Replace the overlay in one write.
     *
     * <p>Every argument may be null or blank, meaning "no override" - the next
     * read then falls through to the environment default. There is no partial
     * update: the form always submits every field, so there is nothing to merge
     * with what was there before.
     */
    @Transactional
    public void update(Integer maxConcurrentRuns, String idleTimeout, String maxDuration,
                       String startupGrace, String workspaceRoot, String branchPrefix,
                       String protectedPaths) {
        PetriSettings settings = row();
        settings.setId(ROW_ID);
        settings.setMaxConcurrentRuns(maxConcurrentRuns);
        settings.setIdleTimeout(blankToNull(idleTimeout));
        settings.setMaxDuration(blankToNull(maxDuration));
        settings.setStartupGrace(blankToNull(startupGrace));
        settings.setWorkspaceRoot(blankToNull(workspaceRoot));
        settings.setBranchPrefix(blankToNull(branchPrefix));
        settings.setProtectedPaths(blankToNull(protectedPaths));
        repository.save(settings);
        LOG.info("Policy settings updated");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Duration parse(String override, Duration fallback) {
        if (override == null || override.isBlank()) {
            return fallback;
        }
        try {
            return Duration.parse(override);
        } catch (RuntimeException ex) {
            LOG.warn("Stored duration '{}' is not valid ISO-8601; falling back to {}", override, fallback);
            return fallback;
        }
    }
}
