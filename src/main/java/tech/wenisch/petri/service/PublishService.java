package tech.wenisch.petri.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.WorkflowState;
import tech.wenisch.petri.gateway.AgentGateway;
import tech.wenisch.petri.gateway.GateReport;
import tech.wenisch.petri.gateway.GatewayException;
import tech.wenisch.petri.repository.CardRepository;

/**
 * Pushes a branch and opens a pull request, once a card reaches a state that
 * publishes.
 *
 * <p>This is the step that turns a card into something a person can act on.
 * Everything before it happens inside the gateway's workspace and is invisible
 * from the forge.
 *
 * <p>There is deliberately no merge, here or anywhere. Landing a change is a
 * decision for a person, and nothing in Petri should be able to make it.
 */
@Service
public class PublishService {

    private static final Logger LOG = LoggerFactory.getLogger(PublishService.class);

    private final AgentGateway gateway;
    private final CardRepository cards;

    public PublishService(AgentGateway gateway, CardRepository cards) {
        this.gateway = gateway;
        this.cards = cards;
    }

    /**
     * @return a description of what happened, for the card's history
     */
    @Transactional
    public String publish(Card card, WorkflowState state) {
        if (!state.isPublish()) {
            return null;
        }
        if (card.getPullRequestUrl() != null && !card.getPullRequestUrl().isBlank()) {
            // Already published. Re-entering a publishing state - after a
            // rejection sent the card back and it came round again - must not
            // open a second pull request for the same branch.
            LOG.debug("Card {} already has a pull request", card.getId());
            return "already published";
        }

        String repository = card.getBoard().getRepository();
        String branch = card.getBranch();
        if (branch == null || branch.isBlank()) {
            return "nothing to publish: the card has no branch";
        }

        try {
            // The gateway re-runs its own gate on the rebased result before it
            // pushes, so a refusal here is a real refusal and not a formality.
            GateReport pushed = gateway.push(repository, branch);
            if (!pushed.passed()) {
                LOG.warn("Card {} was not pushed: {}", card.getId(), pushed.output());
                return "push refused: " + pushed.output();
            }

            String url = gateway.openPullRequest(repository, branch, card.getTitle(), body(card));
            card.setPullRequestUrl(url);
            cards.save(card);

            LOG.info("Card {} published as {}", card.getId(), url);
            return "pull request opened: " + url;

        } catch (GatewayException ex) {
            LOG.warn("Card {} could not be published: {}", card.getId(), ex.getMessage());
            return "could not publish: " + ex.getMessage();
        }
    }

    /**
     * Say plainly where the change came from.
     *
     * <p>A reviewer's first question is what produced this and whether anything
     * human has looked at it, so the answer goes in the body rather than being
     * left to be inferred from the branch name.
     */
    private String body(Card card) {
        return """
                Petri card #%d: %s

                %s

                Written by an agent, checked by the repository gate, and reviewed
                by a model. No human has read this diff yet.
                """.formatted(
                card.getId(),
                card.getTitle(),
                card.getDescription() == null ? "" : card.getDescription());
    }
}
