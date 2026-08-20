package tech.wenisch.petri.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Append-only record of a card moving between states.
 *
 * <p>A status field says what a card is; this says how it got there - which
 * model produced the change, on which attempt, past which gate, and what the
 * verdict was. When an agent does something surprising, this is the only way to
 * find out why.
 */
@Entity
@Table(name = "transition")
@Getter
@Setter
@NoArgsConstructor
public class Transition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    /** Null for the transition that puts a card on the board. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_state_id")
    private WorkflowState fromState;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_state_id", nullable = false)
    private WorkflowState toState;

    /** The run that caused this, when a run did. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id")
    private AgentRun run;

    /** Model alias, gate name, or a person. */
    @Column(nullable = false, length = 128)
    private String actor;

    @Column
    private String verdict;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
