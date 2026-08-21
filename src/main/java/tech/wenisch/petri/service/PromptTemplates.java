package tech.wenisch.petri.service;

import tech.wenisch.petri.entity.GateType;

/**
 * The contract between Petri and whatever agent it is driving.
 *
 * <p>These are defaults. A state can override them, but the shape matters: the
 * agent commits and reports, and a <em>later</em> state pushes. Nothing reaches
 * the forge until Petri has read the diff and its gates have passed, which is
 * what keeps a credential out of the remote's history - once a branch is pushed,
 * a secret is in it whatever anyone decides afterwards.
 *
 * <p>Written as instructions rather than enforced, because no two agent harnesses
 * enforce the same way. What actually constrains the agent is the scope of its
 * token and branch protection on the forge; this only has to be clear enough
 * that a cooperating agent does the right thing.
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    /**
     * The prompt a state gets when it has not been given one.
     *
     * <p>Chosen by gate, because the gate is what the state's output has to
     * satisfy: a plan-shape gate wants a plan, a repository gate wants a diff,
     * a verdict gate wants something to judge. A state whose gate implies no
     * particular shape gets the task and nothing else - including the pushing
     * state, whose gate is NONE and which therefore has to be given
     * {@link #PUSH} explicitly. That is deliberate: guessing that every
     * ungated state wants to push would be a bad guess to make silently.
     */
    public static String defaultFor(GateType gate) {
        return switch (gate) {
            case PLAN_SHAPE -> PLAN;
            case REPOSITORY -> IMPLEMENT;
            case LLM_VERDICT -> REVIEW;
            case NONE, HUMAN -> "{{title}}\n\n{{description}}";
        };
    }

    /** Clone if needed, work, commit, and report - but do not push. */
    public static final String IMPLEMENT = """
            You are working on a git repository as part of an automated pipeline.

            Repository: {{repository}}
            Clone URL:  {{cloneUrl}}
            Branch:     {{branch}}
            Workspace:  {{workspace}}

            Do this, in order:

            1. If the workspace is not already a clone of the repository, clone it
               there. If it is, fetch the default branch.
            2. Check out {{branch}}, creating it from the default branch if needed.
            3. Make the change described below.
            4. Commit your work with a clear message.
            5. Do NOT push. Do NOT open a pull request. Something else does that
               once your change has been checked.
            6. Finish your reply with the complete diff of your commits against the
               default branch, inside a fenced block marked ```diff. The diff is
               what gets reviewed, so it must be the whole change, not a summary.

            Task: {{title}}

            {{description}}
            """;

    /**
     * Put the change in front of the reviewing model.
     *
     * <p>A turn of its own, rather than reusing what the implementer said,
     * because a gate reads the run in its own state. The turn is cheap - it is
     * one git command - and the alternative is a gate that reaches backwards
     * into another state's history to find something to judge.
     */
    public static final String REVIEW = """
            The change on branch {{branch}} in {{workspace}} is about to be
            reviewed.

            Do not change anything. Reply with the complete diff of {{branch}}
            against the default branch, inside a fenced block marked ```diff, and
            a short note of what it does.
            """;

    /** Push what was already committed and checked. */
    public static final String PUSH = """
            The change you committed in this workspace has been reviewed and
            approved.

            Push branch {{branch}} to the remote. Do not force-push, do not touch
            the default branch, and do not open a pull request - Petri opens it.

            Reply with the result of the push and nothing else.
            """;

    /** Plan the work before anyone writes code. */
    public static final String PLAN = """
            Plan the following task before any code is written.

            Repository: {{repository}}

            Name the files that need to change and the acceptance criteria - how
            anyone would know the work is done. A plan without those is not usable
            and will be rejected.

            Do not change any files. Reply with the plan only.

            Task: {{title}}

            {{description}}
            """;
}
