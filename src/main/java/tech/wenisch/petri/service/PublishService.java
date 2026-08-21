package tech.wenisch.petri.service;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.entity.Forge;
import tech.wenisch.petri.forge.BranchChange;
import tech.wenisch.petri.forge.ForgeClient;
import tech.wenisch.petri.forge.ForgeException;
import tech.wenisch.petri.forge.PullRequestRef;
import tech.wenisch.petri.gate.ChangeInspector;

/**
 * Verifies what actually landed, then opens the pull request.
 *
 * <p>This is the second inspection. The first ran on the diff the agent
 * <em>reported</em>, before the push, and stopped a mistake reaching the remote.
 * This one reads what is really on the branch, and catches an agent whose report
 * did not match its commits - a case the first check cannot see by construction.
 *
 * <p>Petri opens the pull request rather than the agent, for four reasons that
 * all showed up in practice: a rejected change never gets one at all; the body
 * can say which gates passed and which models were involved, which the agent
 * does not know; a card that comes round twice does not open a second; and its
 * existence is a fact Petri established rather than a claim it has to believe.
 *
 * <p>There is no merge, here or anywhere. Landing a change is a person's
 * decision.
 */
@Service
public class PublishService {

    private static final Logger LOG = LoggerFactory.getLogger(PublishService.class);

    private final Map<Forge, ForgeClient> forges;
    private final ChangeInspector inspector;
    private final PetriMetrics metrics;

    public PublishService(Map<Forge, ForgeClient> forges,
                          ChangeInspector inspector,
                          PetriMetrics metrics) {
        this.forges = forges;
        this.inspector = inspector;
        this.metrics = metrics;
    }

    /** @return what happened, for the card's history, or null if nothing was due */
    public String publish(Card card, boolean publishing) {
        if (!publishing) {
            return null;
        }
        if (card.getPullRequestUrl() != null && !card.getPullRequestUrl().isBlank()) {
            // Already published. A card that was rejected, went back, and came
            // round again must not open a second pull request for one branch.
            return "already published";
        }

        String branch = card.getBranch();
        if (branch == null || branch.isBlank()) {
            return "nothing to publish: the card has no branch";
        }

        ForgeClient forge = forges.get(card.getBoard().getForge());
        if (forge == null) {
            return "no client configured for " + card.getBoard().getForge();
        }

        String repository = card.getBoard().getRepository();
        String base = card.getBoard().getDefaultBranch();

        try {
            BranchChange landed = forge.change(repository, base, branch);
            if (landed.isEmpty()) {
                // The agent said it pushed and nothing is there. Reporting this
                // as published would be worse than useless.
                metrics.published(false);
                return "nothing was pushed to " + branch;
            }

            List<String> problems = inspector.inspect(landed, branch, base);
            if (!problems.isEmpty()) {
                // What landed differs from what was approved. Remove it rather
                // than leave a rejected branch sitting on the forge.
                LOG.warn("Card {} failed inspection after push: {}", card.getId(), problems);
                forge.deleteBranch(repository, branch);
                metrics.published(false);
                return "refused after push: " + String.join("; ", problems);
            }

            PullRequestRef pullRequest = forge.openPullRequest(
                    repository, base, branch, card.getTitle(), body(card, landed));
            card.setPullRequestUrl(pullRequest.url());
            metrics.published(true);

            LOG.info("Card {} published as {}", card.getId(), pullRequest.url());
            return "pull request opened: " + pullRequest.url();

        } catch (ForgeException ex) {
            metrics.published(false);
            LOG.warn("Card {} could not be published: {}", card.getId(), ex.getMessage());
            return "could not publish: " + ex.getMessage();
        }
    }

    /**
     * Say where the change came from and what was checked.
     *
     * <p>A reviewer's first question is what produced this and whether anything
     * human has seen it. That answer belongs in the body rather than being left
     * to be inferred from a branch name.
     */
    private String body(Card card, BranchChange landed) {
        return """
                Petri card #%d: %s

                %s

                %d commit(s) across %d file(s). Written by an agent, inspected
                before the push and again after it, and reviewed by a model.

                **No human has read this diff yet.**
                """.formatted(
                card.getId(),
                card.getTitle(),
                card.getDescription() == null ? "" : card.getDescription(),
                landed.commits().size(),
                landed.files().size());
    }
}
