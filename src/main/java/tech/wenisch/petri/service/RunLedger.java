package tech.wenisch.petri.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.repository.*;

/**
 * Every database write around a run, each in its own short transaction.
 *
 * <p>This exists to keep transactions off the far side of a network call. The
 * runner used to hold one transaction open for the whole cycle, including the
 * call to the agent - so a slow or hanging agent held a database connection for
 * the length of a turn, which can be an hour. At one card at a time that is
 * merely wasteful; with concurrency it exhausts the pool.
 *
 * <p>It is also a separate bean on purpose. {@code @Transactional} is applied by
 * a proxy, so a method calling another on {@code this} goes straight past it -
 * the mistake that made every scheduled cycle run with no transaction at all.
 */
@Service
public class RunLedger {

    private static final Logger LOG = LoggerFactory.getLogger(RunLedger.class);

    /**
     * A claimed piece of work, carrying everything the agent call needs.
     *
     * <p>Flattened deliberately: the caller is outside a transaction by the time
     * it uses this, so an entity here would be a detached proxy waiting to throw.
     */
    public record ClaimedWork(Long runId, Long cardId, String repository,
                              String branch, String prompt) {
    }

    private final BoardRepository boards;
    private final WorkflowStateRepository states;
    private final CardRepository cards;
    private final AgentRunRepository runs;

    public RunLedger(BoardRepository boards,
                     WorkflowStateRepository states,
                     CardRepository cards,
                     AgentRunRepository runs) {
        this.boards = boards;
        this.states = states;
        this.cards = cards;
        this.runs = runs;
    }

    /**
     * Take the next eligible card and record that work is starting on it.
     *
     * <p>Claiming consumes an attempt, whether or not the agent can be reached.
     * A card that cannot be started must not be retried forever.
     */
    @Transactional
    public Optional<ClaimedWork> claim() {
        // One card at a time while the workspace is shared state on the agent's
        // side. This goes away once each card gets its own session directory.
        if (!openRuns().isEmpty()) {
            return Optional.empty();
        }

        for (Board board : boards.findByEnabledTrue()) {
            for (WorkflowState state : states.findByBoardOrderByPositionAsc(board)) {
                if (!state.isAutomated()) {
                    continue;
                }
                for (Card card : cards.findByState(state)) {
                    if (hasOpenRun(card) || exhausted(card, state)) {
                        continue;
                    }
                    return Optional.of(open(card, state));
                }
            }
        }
        return Optional.empty();
    }

    private ClaimedWork open(Card card, WorkflowState state) {
        String branch = branchFor(card);
        card.setAttempts(card.getAttempts() + 1);
        cards.save(card);

        AgentRun run = new AgentRun();
        run.setCard(card);
        run.setState(state);
        run.setAttempt(card.getAttempts());
        run.setStatus(RunStatus.PENDING);
        run.setStartedAt(Instant.now());
        runs.save(run);

        return new ClaimedWork(run.getId(), card.getId(),
                card.getBoard().getRepository(), branch, prompt(card, state));
    }

    @Transactional
    public void markStarted(Long runId, String sessionId) {
        runs.findById(runId).ifPresent(run -> {
            run.setSessionId(sessionId);
            run.setStatus(RunStatus.RUNNING);
            // Seeded so silence is measured from when work began, rather than
            // from an absent event that would make a fresh run look infinitely
            // quiet and be stopped immediately.
            run.setLastEventAt(Instant.now());
            runs.save(run);
            LOG.info("Run {} started as session {}", runId, sessionId);
        });
    }

    @Transactional
    public void markFailed(Long runId, String reason) {
        runs.findById(runId).ifPresent(run -> {
            run.setStatus(RunStatus.FAILED);
            run.setFinishedAt(Instant.now());
            run.setSummary(reason);
            runs.save(run);
            LOG.warn("Run {} could not be started: {}", runId, reason);
        });
    }

    @Transactional(readOnly = true)
    public List<AgentRun> openRuns() {
        return runs.findByStatusIn(List.of(RunStatus.PENDING, RunStatus.RUNNING));
    }

    private boolean hasOpenRun(Card card) {
        return runs.findFirstByCardOrderByIdDesc(card)
                .map(run -> !run.getStatus().isFinished())
                .orElse(false);
    }

    private boolean exhausted(Card card, WorkflowState state) {
        if (card.getAttempts() < state.getMaxAttempts()) {
            return false;
        }
        LOG.debug("Card {} has used its {} attempts in {}",
                card.getId(), state.getMaxAttempts(), state.getName());
        return true;
    }

    /** The branch owns the session, so a card without one gets a stable name now. */
    private String branchFor(Card card) {
        if (card.getBranch() != null && !card.getBranch().isBlank()) {
            return card.getBranch();
        }
        String slug = card.getTitle().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        String branch = "petri/" + card.getId() + "-"
                + (slug.length() > 40 ? slug.substring(0, 40) : slug);
        card.setBranch(branch);
        cards.save(card);
        return branch;
    }

    private String prompt(Card card, WorkflowState state) {
        String template = state.getPromptTemplate();
        if (template == null || template.isBlank()) {
            template = "{{title}}\n\n{{description}}";
        }
        return template
                .replace("{{title}}", card.getTitle())
                .replace("{{description}}", card.getDescription() == null ? "" : card.getDescription())
                .replace("{{branch}}", card.getBranch() == null ? "" : card.getBranch())
                .replace("{{state}}", state.getName());
    }
}
