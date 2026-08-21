package tech.wenisch.petri.forge;

import java.util.ArrayList;
import java.util.List;

/**
 * What a branch adds on top of its base.
 *
 * <p>Carries the diff of <em>every commit</em> rather than one squashed patch.
 * That distinction is the whole reason the secret scan works: a credential added
 * in one commit and removed in a later one cancels out of a net diff while
 * staying in the history that gets pushed.
 *
 * @param commits    commit ids, oldest first
 * @param files      paths touched across the range
 * @param patches    unified diff per commit, in the same order as {@code commits}
 */
public record BranchChange(List<String> commits, List<ChangedFile> files, List<String> patches) {

    public boolean isEmpty() {
        return commits.isEmpty();
    }

    /** Every patch joined, for handing to a reviewer that wants one document. */
    public String combinedPatch() {
        return String.join("\n", patches);
    }

    /**
     * Read a change out of a diff the agent reported, before anything is pushed.
     *
     * <p>Necessarily one pseudo-commit: a reported patch has no commit
     * boundaries. That is weaker than what the forge returns afterwards, and it
     * is why the same inspection runs twice - once here to stop a mistake
     * reaching the remote, and once on the forge to catch a report that did not
     * match the commits.
     */
    public static BranchChange fromReportedPatch(String patch) {
        if (patch == null || patch.isBlank()) {
            return new BranchChange(List.of(), List.of(), List.of());
        }

        List<ChangedFile> files = new ArrayList<>();
        for (String line : patch.split("\\R")) {
            if (!line.startsWith("diff --git ")) {
                continue;
            }
            // "diff --git a/path b/path" - take the b-side, because for a rename
            // or a delete the a-side is only where the file used to be.
            int marker = line.lastIndexOf(" b/");
            if (marker > 0) {
                files.add(new ChangedFile(line.substring(marker + 3).trim(), "reported"));
            }
        }

        return new BranchChange(List.of("reported"), List.copyOf(files), List.of(patch));
    }
}
