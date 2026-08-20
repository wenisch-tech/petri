package tech.wenisch.petri.entity;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One execution of one state's action against one card.
 *
 * <p>Runs are started, not awaited. Blocking on a single request for the whole
 * of a turn leaves no channel to ask whether anything is still alive, because
 * the only channel is busy carrying the answer.
 */
@Entity
@Table(name = "agent_run")
@Getter
@Setter
@NoArgsConstructor
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "state_id", nullable = false)
    private WorkflowState state;

    @Column(nullable = false)
    private int attempt;

    /** Agent session this run drives, once the gateway has reported one. */
    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RunStatus status = RunStatus.PENDING;

    @Column(name = "started_at")
    private Instant startedAt;

    /**
     * When the agent last produced anything.
     *
     * <p>Kept alongside {@link #status} because status alone distinguishes
     * nothing: a session reports busy for the whole of a single model call, and
     * such a call can legitimately take minutes. Working and hung look identical
     * until you also know how long it has been silent.
     */
    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column
    private String summary;

    /** How long the run has produced nothing, or empty if it never started. */
    public Duration silenceFor(Instant now) {
        Instant since = lastEventAt != null ? lastEventAt : startedAt;
        return since == null ? Duration.ZERO : Duration.between(since, now);
    }
}
