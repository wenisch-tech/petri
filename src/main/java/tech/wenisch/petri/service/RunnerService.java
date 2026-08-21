package tech.wenisch.petri.service;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.gateway.AgentGateway;
import tech.wenisch.petri.gateway.GatewayException;
import tech.wenisch.petri.gateway.StartRequest;

/**
 * Starts work for cards sitting in a state that has a model bound to it.
 *
 * <p>Deliberately <strong>not</strong> transactional. The database writes happen
 * in {@link RunLedger}, each in its own short transaction, with the call to the
 * agent in between and outside all of them. Holding a transaction across that
 * call means one connection is pinned for as long as a turn takes, which can be
 * an hour.
 *
 * <p>Starting is asynchronous: the agent is asked to begin and answers with a
 * session id, and everything after that is observation. An orchestrator that
 * blocks on a turn cannot answer "is it still alive?", because the only channel
 * it has is busy carrying the answer.
 */
@Service
public class RunnerService {

    private static final Logger LOG = LoggerFactory.getLogger(RunnerService.class);

    private final RunLedger ledger;
    private final AgentGateway gateway;
    private final PetriMetrics metrics;

    public RunnerService(RunLedger ledger, AgentGateway gateway, PetriMetrics metrics) {
        this.ledger = ledger;
        this.gateway = gateway;
        this.metrics = metrics;
    }

    public void startEligibleWork() {
        Optional<RunLedger.ClaimedWork> claimed = ledger.claim();
        if (claimed.isEmpty()) {
            return;
        }

        RunLedger.ClaimedWork work = claimed.get();
        try {
            String sessionId = gateway.start(new StartRequest(
                    work.workspace(), work.repository(), work.cloneUrl(),
                    work.branch(), work.prompt()));
            ledger.markStarted(work.runId(), sessionId);
            metrics.runStarted();

        } catch (GatewayException ex) {
            // The claim already stands, so the attempt is spent and the run is
            // recorded as failed rather than vanishing. A run that exists but was
            // never started is the state the liveness poller knows how to reap.
            ledger.markFailed(work.runId(), ex.getMessage());
            LOG.warn("Card {} could not be started: {}", work.cardId(), ex.getMessage());
        }
    }

    /** Sessions the liveness poller still needs to watch. */
    public List<AgentRun> openRuns() {
        return ledger.openRuns();
    }
}
