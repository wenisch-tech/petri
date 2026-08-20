package tech.wenisch.petri.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One column on the board, and everything about how work leaves it.
 *
 * <p>A state binds a model, a prompt and a gate, and names where a card goes on
 * pass and on fail. Adding a role - a planner ahead of the implementer, a second
 * reviewer - is a row here rather than a code change.
 */
@Entity
@Table(name = "workflow_state",
        uniqueConstraints = @UniqueConstraint(name = "uk_state_board_name",
                columnNames = {"board_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
public class WorkflowState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    @Column(nullable = false, length = 64)
    private String name;

    /** Left-to-right order on the board. */
    @Column(name = "position", nullable = false)
    private int position;

    /** A terminal state has no outgoing transitions; work stops here. */
    @Column(nullable = false)
    private boolean terminal = false;

    /** LiteLLM model alias driving this state, or null when a human owns it. */
    @Column(name = "model_alias", length = 128)
    private String modelAlias;

    @Column(name = "prompt_template")
    private String promptTemplate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GateType gate = GateType.NONE;

    /**
     * Nullable self-references so a pipeline can be written in any order and
     * linked afterwards. Both null means the card stops here.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "next_on_pass")
    private WorkflowState nextOnPass;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "next_on_fail")
    private WorkflowState nextOnFail;

    /** Attempts in this state before the card stops being retried. */
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    /** True when the runner should start work for cards sitting here. */
    public boolean isAutomated() {
        return modelAlias != null && !terminal;
    }
}
