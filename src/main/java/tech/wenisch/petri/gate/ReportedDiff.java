package tech.wenisch.petri.gate;

/**
 * Pulls the diff out of what an agent said.
 *
 * <p>The agent is asked to emit its diff in a fenced block. Models are
 * inconsistent about fencing, so a bare {@code diff --git} is accepted too -
 * being strict here would turn a formatting slip into a failed card.
 */
public final class ReportedDiff {

    private ReportedDiff() {
    }

    public static String from(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }

        String fenced = betweenFences(output);
        if (!fenced.isBlank()) {
            return fenced;
        }

        int start = output.indexOf("diff --git ");
        return start < 0 ? "" : output.substring(start).strip();
    }

    /** The contents of the first ```diff block, if there is one. */
    private static String betweenFences(String output) {
        int open = output.indexOf("```diff");
        if (open < 0) {
            return "";
        }
        int contentStart = output.indexOf('\n', open);
        if (contentStart < 0) {
            return "";
        }
        int close = output.indexOf("```", contentStart);
        return close < 0
                ? output.substring(contentStart + 1).strip()
                : output.substring(contentStart + 1, close).strip();
    }
}
