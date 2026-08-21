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

    /** Plain clone URL. Never carries a credential. */
    String cloneUrl(String repository);

    /**
     * The clone URL to put in front of the agent.
     *
     * <p>The same as {@link #cloneUrl} unless the operator switched credential
     * handover on, in which case it embeds one. Separate from {@code cloneUrl}
     * on purpose: a method that sometimes returns a secret should say so in its
     * name, or someone will log it.
     */
    String cloneUrlForAgent(String repository);
}
