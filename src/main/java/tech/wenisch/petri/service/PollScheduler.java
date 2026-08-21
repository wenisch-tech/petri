package tech.wenisch.petri.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the two polling loops from outside the services they call.
 *
 * <p>This class exists for one reason: {@code @Transactional} is applied by a
 * proxy, and a method calling another method on {@code this} goes straight past
 * it. The schedulers used to live on the services themselves, so every cycle ran
 * with no transaction and died on the first lazy association - while the tests
 * passed, because they call the injected proxy directly and therefore do get a
 * transaction. Only running the application showed it.
 */
@Component
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
