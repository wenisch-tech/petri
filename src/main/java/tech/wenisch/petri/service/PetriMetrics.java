package tech.wenisch.petri.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.RunStatus;
import tech.wenisch.petri.gate.GateOutcome;
import tech.wenisch.petri.repository.AgentRunRepository;

/**
 * What the board looks like to a monitoring system.
 *
 * <p>The gauge that earns its place is the silence one. Counters say how much
 * work happened; only time-since-last-event distinguishes an agent that is
 * thinking from one that has stopped, and that difference is invisible from
 * status alone.
 */
@Component
public class PetriMetrics {

    private final MeterRegistry registry;
    private final AgentRunRepository runs;

    public PetriMetrics(MeterRegistry registry, AgentRunRepository runs) {
        this.registry = registry;
        this.runs = runs;

        registry.gauge("petri.runs.open", this, PetriMetrics::openRunCount);
        // Seconds since the quietest open run last produced anything. Alert on
        // this rather than on run duration: a long run is normal, a silent one
        // is not.
        registry.gauge("petri.runs.silence.seconds", this, PetriMetrics::longestSilenceSeconds);
    }

    public void runStarted() {
        Counter.builder("petri.runs.started").register(registry).increment();
    }

    public void runFinished(RunStatus status) {
        Counter.builder("petri.runs.finished")
                .tags(Tags.of("status", status.name().toLowerCase()))
                .register(registry)
                .increment();
    }

    public void gateEvaluated(String gate, GateOutcome.Decision decision) {
        Counter.builder("petri.gates.evaluated")
                .tags(Tags.of("gate", gate.toLowerCase(), "decision", decision.name().toLowerCase()))
                .register(registry)
                .increment();
    }

    public void published(boolean succeeded) {
        Counter.builder("petri.cards.published")
                .tags(Tags.of("result", succeeded ? "success" : "failure"))
                .register(registry)
                .increment();
    }

    private double openRunCount() {
        return open().size();
    }

    private double longestSilenceSeconds() {
        Instant now = Instant.now();
        return open().stream()
                .map(run -> run.silenceFor(now))
                .mapToLong(Duration::getSeconds)
                .max()
                .orElse(0L);
    }

    private List<AgentRun> open() {
        try {
            return runs.findByStatusIn(List.of(RunStatus.PENDING, RunStatus.RUNNING));
        } catch (RuntimeException ex) {
            // A scrape must never take the application down with it.
            return List.of();
        }
    }
}
