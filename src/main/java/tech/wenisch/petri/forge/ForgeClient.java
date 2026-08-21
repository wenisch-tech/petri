package tech.wenisch.petri.forge;

import tech.wenisch.petri.entity.Forge;

/**
 * What Petri needs from a git forge.
 *
 * <p>Deliberately small. Petri never clones and never pushes - the agent does
 * that with its own credential. Petri reads what landed, decides, and either
 * opens a pull request or removes the branch.
 */
public interface ForgeClient {

    Forge forge();

    /** What {@code head} adds on top of {@code base}, with a patch per commit. */
    BranchChange change(String repository, String base, String head);

    /** Open a pull request from {@code head} into {@code base}. Never merges. */
    PullRequestRef openPullRequest(String repository, String base, String head,
                                   String title, String body);

    /** Remove a rejected branch, so a failed attempt leaves nothing behind. */
    void deleteBranch(String repository, String branch);

    /** Clone URL for the agent. Carries no credential; the agent supplies its own. */
    String cloneUrl(String repository);
}
