package tech.wenisch.petri.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.entity.Card;
import tech.wenisch.petri.forge.BranchChange;
import tech.wenisch.petri.forge.ForgeClient;
import tech.wenisch.petri.forge.ForgeClientRegistry;
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

    private final ForgeClientRegistry forges;
    private final ChangeInspector inspector;
    private final PolicySettingsService settings;
    private final PetriMetrics metrics;

    public PublishService(ForgeClientRegistry forges,
                          ChangeInspector inspector,
                          PolicySettingsService settings,
                          PetriMetrics metrics) {
        this.forges = forges;
        this.inspector = inspector;
        this.settings = settings;
        this.metrics = metrics;
    }

    /**
     * What publishing did.
     *
     * @param note           for the card's history
     * @param pullRequestUrl the new pull request, or null if none was opened.
     *                       Returned rather than written onto the card, because
     *                       the caller is outside a transaction and a write to a
     *                       detached entity would be silently lost.
     */
    public record Published(String note, String pullRequestUrl) {
    }

    /** @return what happened, or null if nothing was due */
    public Published publish(Card card, boolean publishing) {
        if (!publishing) {
            return null;
        }
        if (card.getPullRequestUrl() != null && !card.getPullRequestUrl().isBlank()) {
            // Already published. A card that was rejected, went back, and came
            // round again must not open a second pull request for one branch.
            return new Published("already published", null);
        }

        String branch = card.getBranch();
        if (branch == null || branch.isBlank()) {
            return new Published("nothing to publish: the card has no branch", null);
        }

        ForgeClient forge = forges.get(card.getBoard().getForge()).orElse(null);
        if (forge == null) {
            return new Published("no client configured for " + card.getBoard().getForge(), null);
        }

        String repository = card.getBoard().getRepository();
        String base = card.getBoard().getDefaultBranch();

        try {
            BranchChange landed = forge.change(repository, base, branch);
            if (landed.isEmpty()) {
                // The agent said it pushed and nothing is there. Reporting this
                // as published would be worse than useless.
                metrics.published(false);
                return new Published("nothing was pushed to " + branch, null);
            }

            List<String> problems = inspector.inspect(landed, branch, base,
                    settings.protectedPaths(), settings.branchPrefix());
            if (!problems.isEmpty()) {
                // What landed differs from what was approved. Remove it rather
                // than leave a rejected branch sitting on the forge.
                LOG.warn("Card {} failed inspection after push: {}", card.getId(), problems);
                forge.deleteBranch(repository, branch);
                metrics.published(false);
                return new Published("refused after push: " + String.join("; ", problems), null);
            }

            PullRequestRef pullRequest = forge.openPullRequest(
                    repository, base, branch, card.getTitle(), body(card, landed));
            metrics.published(true);

            LOG.info("Card {} published as {}", card.getId(), pullRequest.url());
            return new Published("pull request opened: " + pullRequest.url(), pullRequest.url());

        } catch (ForgeException ex) {
            metrics.published(false);
            LOG.warn("Card {} could not be published: {}", card.getId(), ex.getMessage());
            return new Published("could not publish: " + ex.getMessage(), null);
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
