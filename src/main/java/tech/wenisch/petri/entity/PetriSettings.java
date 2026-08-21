package tech.wenisch.petri.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The operator-editable overlay on top of environment configuration.
 *
 * <p>One row, fixed at id 1. Every field is nullable, and null means "not
 * overridden here" rather than "off" or "zero" - the merge with the
 * environment default happens in {@code PolicySettingsService}, not here.
 */
@Entity
@Table(name = "petri_settings")
@Getter
@Setter
@NoArgsConstructor
public class PetriSettings {

    @Id
    private Long id = 1L;

    @Column(name = "max_concurrent_runs")
    private Integer maxConcurrentRuns;

    /** ISO-8601, e.g. {@code PT15M}. Parsed by the service, not here. */
    @Column(name = "idle_timeout")
    private String idleTimeout;

    @Column(name = "max_duration")
    private String maxDuration;

    @Column(name = "startup_grace")
    private String startupGrace;

    @Column(name = "workspace_root")
    private String workspaceRoot;

    @Column(name = "branch_prefix")
    private String branchPrefix;

    /** Comma-separated globs, the same shape {@code ChangeInspector} already takes. */
    @Column(name = "protected_paths")
    private String protectedPaths;
}
