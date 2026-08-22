package tech.wenisch.petri.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import tech.wenisch.petri.forge.ForgeClientRegistry;
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
    public record ClaimedWork(Long runId, Long cardId, String workspace, String repository,
                              String cloneUrl, String branch, String prompt) {
    }

    /**
     * A run as the poller needs it, with nothing lazy left on it.
     *
     * <p>The poller works outside a transaction, so an entity here would be a
     * detached proxy waiting to throw on the first association it touches.
     */
    public record OpenRun(Long runId, String sessionId, Instant startedAt, Instant lastEventAt) {

        /** How long this run has produced nothing, measured from its last sign of life. */
        public java.time.Duration silenceFor(Instant now) {
            Instant since = lastEventAt != null ? lastEventAt : startedAt;
            return since == null ? java.time.Duration.ZERO : java.time.Duration.between(since, now);
        }
    }

    /**
     * Everything a gate reads, loaded and initialised before the transaction ends.
     *
     * <p>These are entities rather than a projection because the gates take
     * entities, and they are detached rather than managed because deciding
     * involves a model call - which must not happen with a database connection
     * held open. Every association a gate or a transition touches is walked here
     * on purpose; leaving one lazy would move the failure to a scheduled thread
     * where no test would see it.
     */
    public record GateInput(Card card, AgentRun run, WorkflowState state) {
    }

    private final ForgeClientRegistry forges;
    private final Redactor redactor;
    private final PolicySettingsService settings;
    private final BoardRepository boards;
    private final WorkflowStateRepository states;
    private final CardRepository cards;
    private final AgentRunRepository runs;

    public RunLedger(ForgeClientRegistry forges,
                     Redactor redactor,
                     PolicySettingsService settings,
                     BoardRepository boards,
                     WorkflowStateRepository states,
                     CardRepository cards,
                     AgentRunRepository runs) {
        this.forges = forges;
        this.redactor = redactor;
        this.settings = settings;
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
        // Bounded, not serialised. Cards no longer share a workspace, so the
        // limit is about what the agent can actually run at once rather than
        // about Petri. Left at one by default because the reference deployment
        // serialises at the model: a second turn queued behind the first
        // produces nothing while it waits, which is indistinguishable from a
        // hung turn and gets it aborted for silence. Raise it only where turns
        // really do run in parallel.
        if (openRuns().size() >= settings.maxConcurrentRuns()) {
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
                workspaceFor(card), card.getBoard().getRepository(),
                cloneUrl(card), branch, prompt(card, state));
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
            run.setSummary(redactor.redact(reason));
            runs.save(run);
            LOG.warn("Run {} could not be started: {}", runId, redactor.redact(reason));
        });
    }

    /** Open runs, flattened for a poller that holds no transaction. */
    @Transactional(readOnly = true)
    public List<OpenRun> openRunViews() {
        return openRuns().stream()
                .map(run -> new OpenRun(run.getId(), run.getSessionId(),
                        run.getStartedAt(), run.getLastEventAt()))
                .toList();
    }

    /** A run is alive and said so. */
    @Transactional
    public void recordEvent(Long runId, Instant lastEventAt, String detail) {
        runs.findById(runId).ifPresent(run -> {
            if (lastEventAt != null) {
                run.setLastEventAt(lastEventAt);
            }
            if (detail != null) {
                run.setSummary(redactor.redact(detail));
            }
            runs.save(run);
        });
    }

    /**
     * Close a run.
     *
     * <p>The agent's last message is passed in rather than fetched here: reading
     * it is an HTTP call, and this method holds a transaction.
     */
    @Transactional
    public void finish(Long runId, RunStatus status, String reason, String output, Instant now) {
        runs.findById(runId).ifPresent(run -> {
            run.setStatus(status);
            run.setFinishedAt(now);
            // Everything the agent said is stored and later rendered on a card,
            // and an agent that hits a git error quotes the URL it was given.
            run.setSummary(redactor.redact(reason));
            run.setOutput(redactor.redact(output));
            runs.save(run);
            LOG.info("Run {} finished: {} ({})", runId, status, reason);
        });
    }

    /** Load a finished run and its card, with every association a gate reads. */
    @Transactional(readOnly = true)
    public Optional<GateInput> gateInput(Long runId) {
        return runs.findById(runId).map(run -> {
            Card card = run.getCard();
            WorkflowState state = run.getState();
            // Touched deliberately, inside the transaction, so what leaves here
            // is safe to read once it has closed.
            card.getTitle();
            card.getBoard().getRepository();
            // The card's own state as well as the run's. They are usually the
            // same row and therefore the same instance, but a card moved while
            // its run was open leaves an untouched proxy here - and the first
            // thing a transition does is read where the card is coming from.
            card.getState().getName();
            state.getName();
            initialise(state.getNextOnPass());
            initialise(state.getNextOnFail());
            return new GateInput(card, run, state);
        });
    }

    private void initialise(WorkflowState state) {
        if (state != null) {
            state.getName();
            state.isPublish();
        }
    }

    /** Store the pull request a detached card was given while outside a transaction. */
    @Transactional
    public void recordPullRequest(Long cardId, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        cards.findById(cardId).ifPresent(card -> {
            card.setPullRequestUrl(url);
            cards.save(card);
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

    /**
     * One workspace per card.
     *
     * <p>Turns in the same workspace see each other's commits, which is what lets
     * one state commit and a later one push. Per card rather than per repository
     * so two cards on one repository never share a tree - the shared-workspace
     * arrangement this replaces is exactly what forced work to run one at a time.
     */
    private String workspaceFor(Card card) {
        return settings.workspaceRoot() + "/card-" + card.getId();
    }

    private String cloneUrl(Card card) {
        return forges.get(card.getBoard().getForge())
                .map(forge -> forge.cloneUrlForAgent(card.getBoard().getRepository()))
                .orElse("");
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
            template = PromptTemplates.defaultFor(state.getGate());
        }
        return template
                .replace("{{workspace}}", workspaceFor(card))
                .replace("{{repository}}", card.getBoard().getRepository())
                .replace("{{cloneUrl}}", cloneUrl(card))
                .replace("{{title}}", card.getTitle())
                .replace("{{description}}", card.getDescription() == null ? "" : card.getDescription())
                .replace("{{branch}}", card.getBranch() == null ? "" : card.getBranch())
                .replace("{{state}}", state.getName());
    }
}
