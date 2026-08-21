package tech.wenisch.petri.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the two polling loops from outside the services they call.
 *
 * <p>Can be switched off with {@code petri.scheduling.enabled=false}, which
 * pauses both loops without scaling the instance down. Tests turn it off because
 * a background cycle competes with them for the same cards - a non-transactional
 * test that creates a card and calls the runner directly would otherwise race a
 * scheduled cycle doing the same thing, and see two runs where it expected one.
 *
 * <p>This class exists for one reason: {@code @Transactional} is applied by a
 * proxy, and a method calling another method on {@code this} goes straight past
 * it. The schedulers used to live on the services themselves, so every cycle ran
 * with no transaction and died on the first lazy association - while the tests
 * passed, because they call the injected proxy directly and therefore do get a
 * transaction. Only running the application showed it.
 */
@Component
@ConditionalOnProperty(name = "petri.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class PollScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PollScheduler.class);

    private final RunnerService runner;
    private final LivenessService liveness;

    public PollScheduler(RunnerService runner, LivenessService liveness) {
        this.runner = runner;
        this.liveness = liveness;
    }

    @Scheduled(fixedDelayString = "${petri.runner.interval:PT10S}")
    public void startWork() {
        try {
            runner.startEligibleWork();
        } catch (RuntimeException ex) {
            // The loop must survive anything: one unusable card cannot be allowed
            // to stop every other board from progressing.
            LOG.error("Runner cycle failed", ex);
        }
    }

    @Scheduled(fixedDelayString = "${petri.liveness.interval:PT10S}")
    public void observe() {
        try {
            liveness.observeOpenRuns();
        } catch (RuntimeException ex) {
            LOG.error("Liveness cycle failed", ex);
        }
    }
}
