package tech.wenisch.petri.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A unit of work moving through a board's states. */
@Entity
@Table(name = "card")
@Getter
@Setter
@NoArgsConstructor
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "state_id", nullable = false)
    private WorkflowState state;

    @Column(nullable = false, length = 512)
    private String title;

    @Column
    private String description;

    /**
     * The branch this card's work lives on. The branch is the unit of work, so
     * it - not the checkout - owns the agent session: a new card starts a clean
     * conversation rather than inheriting an unrelated one.
     */
    @Column(length = 255)
    private String branch;

    @Column(name = "pull_request_url", length = 1024)
    private String pullRequestUrl;

    /** Attempts in the current state, reset on every transition. */
    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
