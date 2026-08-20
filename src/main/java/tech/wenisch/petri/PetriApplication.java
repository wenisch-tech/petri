package tech.wenisch.petri;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Petri - an orchestrator for AI coding agents.
 *
 * <p>Petri is a state machine over work items. Each state binds to a model, a
 * prompt and a gate; a card advances only when its gate passes. Scheduling is
 * enabled here because the runner polls for cards whose state has work to do,
 * rather than executing anything inline with a web request.
 */
@SpringBootApplication
@EnableScheduling
public class PetriApplication {

    public static void main(String[] args) {
        SpringApplication.run(PetriApplication.class, args);
    }
}
