package tech.wenisch.petri.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.AgentRun;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.Transition;
import tech.wenisch.petri.entity.WorkflowState;
import tech.wenisch.petri.repository.CardRepository;
import tech.wenisch.petri.repository.TransitionRepository;

/** Moves a card between states and records why. */
@Service
public class TransitionService {

    private static final Logger LOG = LoggerFactory.getLogger(TransitionService.class);

    private final CardRepository cards;
    private final TransitionRepository transitions;

    public TransitionService(CardRepository cards, TransitionRepository transitions) {
        this.cards = cards;
        this.transitions = transitions;
    }

    /**
     * Move a card, writing the history entry in the same transaction.
     *
     * <p>The record is not optional bookkeeping. A status field says what a card
     * is; this says how it got there, and when an agent does something
     * surprising it is the only way to find out why.
     */
    @Transactional
    public void move(Card card, WorkflowState target, String actor, String verdict, AgentRun run) {
        WorkflowState from = card.getState();

        Transition transition = new Transition();
        transition.setCard(card);
        transition.setFromState(from);
        transition.setToState(target);
        transition.setActor(actor);
        transition.setVerdict(verdict);
        transition.setRun(run);
        transitions.save(transition);

        card.setState(target);
        // Attempts count tries within a state, so a card arriving somewhere new
        // starts from zero. Without this a card that bounced back and forth
        // would exhaust its budget for reasons that belonged to another state.
        card.setAttempts(0);
        cards.save(card);

        LOG.info("Card {} moved {} -> {} by {} ({})",
                card.getId(),
                from == null ? "-" : from.getName(),
                target.getName(),
                actor,
                verdict);
    }

    /** Record a failed attempt without moving the card. */
    @Transactional
    public void recordAttempt(Card card) {
        card.setAttempts(card.getAttempts() + 1);
        cards.save(card);
    }
}
