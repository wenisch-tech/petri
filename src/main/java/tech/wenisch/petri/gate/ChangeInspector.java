package tech.wenisch.petri.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.forge.BranchChange;
import tech.wenisch.petri.forge.ChangedFile;

/**
 * The mechanical checks on a change: paths, branch, and credentials.
 *
 * <p>Separate from any gate so it can run against two different inputs. Before
 * the push it inspects the diff the agent reported; after the push it inspects
 * what actually landed. Both matter and they fail differently - the first stops
 * a mistake reaching the remote, the second catches an agent whose report did
 * not match its commits.
 *
 * <p>Stateless on purpose: {@code protectedPaths} and {@code branchPrefix} are
 * policy that can change while Petri is running, so the caller reads the
 * current value from {@code PolicySettingsService} and passes it in, rather
 * than this class holding a value fixed at startup.
 */
@Component
public class ChangeInspector {

    /**
     * Verbatim from the implementation these replace, which earned them.
     *
     * <p>The assigned-literal rule is the one that catches most real leaks; the
     * other two catch the shapes that are unmistakable.
     */
    private static final List<Rule> SECRET_RULES = List.of(
            new Rule(Pattern.compile(
                    "(?i)(password|passwd|api[_-]?key|secret|token)\\s*[:=]\\s*['\"][^'\"]{8,}"),
                    "assigned password/api_key/secret/token literal"),
            new Rule(Pattern.compile("-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----"),
                    "private key header"),
            new Rule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b"), "sk- style API key"));

    private record Rule(Pattern pattern, String label) {
    }

    /** Problems that must block a push. Empty means nothing objectionable. */
    public List<String> inspect(BranchChange change, String branch, String defaultBranch,
                                List<String> protectedPaths, String branchPrefix) {
        List<String> problems = new ArrayList<>();

        if (branch == null || branch.isBlank()) {
            problems.add("the card has no branch");
            return problems;
        }
        if (branch.equals(defaultBranch)) {
            problems.add("refusing to work on the default branch");
        }
        if (!branch.startsWith(branchPrefix)) {
            problems.add("branch " + branch + " does not start with " + branchPrefix);
        }
        if (change.isEmpty()) {
            problems.add("nothing was committed");
            return problems;
        }

        List<String> blocked = change.files().stream()
                .map(ChangedFile::path)
                .filter(path -> isProtected(path, protectedPaths))
                .toList();
        if (!blocked.isEmpty()) {
            problems.add("changes touch protected paths: " + String.join(", ", blocked));
        }

        problems.addAll(scanForSecrets(change));
        return problems;
    }

    /**
     * Scan every commit, not the combined diff.
     *
     * <p>A credential added in one commit and removed in a later one cancels out
     * of a net diff while remaining in the history that gets pushed. Scanning per
     * commit is the only way to see it.
     *
     * <p>Only the rule and the location are reported. The matched text is never
     * included: this string ends up on a card, in logs and in a pull request
     * body, and a leak detector that prints the leak is not a detector.
     */
    private List<String> scanForSecrets(BranchChange change) {
        List<String> problems = new ArrayList<>();

        for (int index = 0; index < change.patches().size(); index++) {
            String patch = change.patches().get(index);
            String commit = index < change.commits().size()
                    ? change.commits().get(index) : "unknown";

            for (Rule rule : SECRET_RULES) {
                if (containsInAddedLine(patch, rule.pattern())) {
                    problems.add("possible credential in commit "
                            + commit.substring(0, Math.min(8, commit.length()))
                            + ": " + rule.label());
                }
            }
        }
        return problems;
    }

    /**
     * Only added lines count.
     *
     * <p>A diff that <em>removes</em> a credential contains it too, and flagging
     * that would make cleaning up a leak impossible - the fix would be refused
     * for containing the thing it deletes.
     */
    private boolean containsInAddedLine(String patch, Pattern pattern) {
        for (String line : patch.split("\\R")) {
            if (line.startsWith("+") && !line.startsWith("+++") && pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean isProtected(String path, List<String> protectedPaths) {
        for (String rule : protectedPaths) {
            if (matches(path, rule.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Glob matching, with {@code **} crossing directory boundaries.
     *
     * <p>Built by scanning rather than by chained replacements. Replacing in
     * sequence corrupts its own output: turning {@code **} into {@code .*} and
     * then {@code *} into {@code [^/]*} rewrites the star that was just
     * inserted, and {@code .github/**} silently stops matching anything nested.
     */
    private boolean matches(String path, String rule) {
        StringBuilder regex = new StringBuilder();
        int index = 0;
        while (index < rule.length()) {
            char current = rule.charAt(index);
            if (current == '*' && index + 1 < rule.length() && rule.charAt(index + 1) == '*') {
                if (index + 2 < rule.length() && rule.charAt(index + 2) == '/') {
                    // Leading "**/" must also match nothing, so a rule like
                    // **/Dockerfile still matches one at the repository root.
                    regex.append("(?:.*/)?");
                    index += 3;
                } else {
                    regex.append(".*");
                    index += 2;
                }
            } else if (current == '*') {
                regex.append("[^/]*");
                index++;
            } else if (current == '?') {
                regex.append('.');
                index++;
            } else {
                regex.append(Pattern.quote(String.valueOf(current)));
                index++;
            }
        }
        return path.matches(regex.toString());
    }
}
