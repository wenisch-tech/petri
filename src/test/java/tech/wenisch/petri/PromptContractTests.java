package tech.wenisch.petri;

import org.junit.jupiter.api.Test;
import tech.wenisch.petri.entity.GateType;
import tech.wenisch.petri.service.PromptTemplates;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instructions Petri gives an agent, pinned.
 *
 * <p>This is the whole safety argument in three sentences of English: commit,
 * do not push, and report the diff. If that wording drifts, the secret scan is
 * inspecting a change that is already on the remote - and nothing else in the
 * system would notice, because every gate would still pass.
 */
class PromptContractTests {

    @Test
    void theImplementingStateIsToldToCommitAndNotToPush() {
        String prompt = PromptTemplates.defaultFor(GateType.REPOSITORY);

        assertThat(prompt).contains("Commit");
        assertThat(prompt).contains("Do NOT push");
        // The gate reads a fenced block. An agent that reports a diff in some
        // other shape reads as having changed nothing, and fails its gate.
        assertThat(prompt).contains("```diff");
    }

    @Test
    void everyPromptCanBeFilledFromWhatTheLedgerKnows() {
        // Placeholders the ledger does not substitute would reach the agent
        // verbatim, as literal braces in its instructions.
        for (GateType gate : GateType.values()) {
            assertThat(placeholdersIn(PromptTemplates.defaultFor(gate)))
                    .as("placeholders in the default prompt for %s", gate)
                    .isSubsetOf("workspace", "repository", "cloneUrl", "title",
                            "description", "branch", "state");
        }
        assertThat(placeholdersIn(PromptTemplates.PUSH))
                .isSubsetOf("workspace", "repository", "cloneUrl", "title",
                        "description", "branch", "state");
    }

    @Test
    void anUngatedStateIsNotQuietlyTurnedIntoAPushingOne() {
        // The pushing state has gate NONE, and so does any number of harmless
        // ones. Inferring "push" from "no gate" would push from all of them.
        assertThat(PromptTemplates.defaultFor(GateType.NONE))
                .doesNotContain("Push")
                .doesNotContain("push");
    }

    private java.util.List<String> placeholdersIn(String template) {
        java.util.List<String> found = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{\\{([a-zA-Z]+)}}").matcher(template);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
