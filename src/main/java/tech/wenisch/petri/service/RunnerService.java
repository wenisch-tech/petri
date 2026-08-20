package tech.wenisch.petri.service;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.*;
import tech.wenisch.petri.gateway.AgentGateway;
import tech.wenisch.petri.gateway.GatewayException;
import tech.wenisch.petri.gateway.StartRequest;
import tech.wenisch.petri.repository.*;

/**
 * Starts work for cards sitting in a state that has a model bound to it.
 *
 * <p>Starting is asynchronous: the gateway is asked to begin and answers with a
 * session id, and everything after that is observation. An orchestrator that
 * blocks on a turn cannot answer "is it still alive?", because the only channel
 * it has is busy carrying the answer.
 */
@Service
public class RunnerService {

    private static final Logger LOG = LoggerFactory.getLogger(RunnerService.class);

    private final BoardRepository boards;
    private final WorkflowStateRepository states;
    private final CardRepository cards;
    private final AgentRunRepository runs;
    private final TransitionService transitionService;
    private final AgentGateway gateway;

    public RunnerService(BoardRepository boards,
                         WorkflowStateRepository states,
                         CardRepository cards,
                         AgentRunRepository runs,
                         TransitionService transitionService,
                         AgentGateway gateway) {
        this.boards = boards;
        this.states = states;
        this.cards = cards;
        this.runs = runs;
        this.transitionService = transitionService;
        this.gateway = gateway;
    }

    @Scheduled(fixedDelayString = "${petri.runner.interval:PT10S}")
    public void poll() {
        try {
            startEligibleWork();
        } catch (RuntimeException ex) {
            // The loop has to survive anything: one unusable card must not stop
            // every other board from progressing.
            LOG.error("Runner cycle failed", ex);
        }
    }

    @Transactional
    public void startEligibleWork() {
        for (Board board : boards.findByEnabledTrue()) {
            for (WorkflowState state : states.findByBoardOrderByPositionAsc(board)) {
                if (!state.isAutomated()) {
                    continue;
                }
                for (Card card : cards.findByState(state)) {
                    if (hasOpenRun(card) || exhausted(card, state)) {
                        continue;
                    }
                    start(card, state);
                }
            }
        }
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

    private void start(Card card, WorkflowState state) {
        AgentRun run = new AgentRun();
        run.setCard(card);
        run.setState(state);
        run.setAttempt(card.getAttempts() + 1);
        run.setStatus(RunStatus.PENDING);
        run.setStartedAt(Instant.now());
        runs.save(run);

        try {
            String sessionId = gateway.start(new StartRequest(
                    card.getBoard().getRepository(),
                    branchFor(card),
                    prompt(card, state)));

            run.setSessionId(sessionId);
            run.setStatus(RunStatus.RUNNING);
            // Seeded so silence is measured from the moment work began, not from
            // an absent event that would make a fresh run look infinitely quiet.
            run.setLastEventAt(Instant.now());
            runs.save(run);

            transitionService.recordAttempt(card);
            LOG.info("Card {} started in {} as session {}", card.getId(), state.getName(), sessionId);

        } catch (GatewayException ex) {
            run.setStatus(RunStatus.FAILED);
            run.setFinishedAt(Instant.now());
            run.setSummary(ex.getMessage());
            runs.save(run);
            transitionService.recordAttempt(card);
            LOG.warn("Card {} could not be started: {}", card.getId(), ex.getMessage());
        }
    }

    /** The branch owns the session, so a card without one gets a stable name now. */
    private String branchFor(Card card) {
        if (card.getBranch() != null && !card.getBranch().isBlank()) {
            return card.getBranch();
        }
        String slug = card.getTitle().toLowerCase().replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-|-$", "");
        String branch = "petri/" + card.getId() + "-" + (slug.length() > 40 ? slug.substring(0, 40) : slug);
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

    /** Sessions the liveness poller still needs to watch. */
    public List<AgentRun> openRuns() {
        return runs.findByStatusIn(List.of(RunStatus.PENDING, RunStatus.RUNNING));
    }
}
