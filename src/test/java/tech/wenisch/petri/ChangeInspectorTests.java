package tech.wenisch.petri;

import org.junit.jupiter.api.Test;
import tech.wenisch.petri.forge.BranchChange;
import tech.wenisch.petri.forge.ChangedFile;
import tech.wenisch.petri.gate.ChangeInspector;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The mechanical checks: branch discipline, protected paths, and credentials. */
class ChangeInspectorTests {

    private final ChangeInspector inspector = new ChangeInspector(
            List.of(".github/**", ".forgejo/**", "Dockerfile", "**/Dockerfile", "*.tfstate"),
            "petri/");

    private BranchChange change(List<String> files, String... patches) {
        return new BranchChange(
                List.of("aaaaaaaa1111", "bbbbbbbb2222").subList(0, patches.length),
                files.stream().map(f -> new ChangedFile(f, "modified")).toList(),
                List.of(patches));
    }

    private String added(String... lines) {
        StringBuilder patch = new StringBuilder("diff --git a/x b/x\n--- a/x\n+++ b/x\n");
        for (String line : lines) {
            patch.append('+').append(line).append('\n');
        }
        return patch.toString();
    }

    @Test
    void aCleanChangePasses() {
        List<String> problems = inspector.inspect(
                change(List.of("src/Main.java"), added("int answer = 42;")),
                "petri/1-thing", "main");

        assertThat(problems).isEmpty();
    }

    @Test
    void aCredentialOnAnAddedLineIsRefused() {
        List<String> problems = inspector.inspect(
                change(List.of("src/Main.java"), added("String apiKey = \"hunter2hunter2\";")),
                "petri/1-thing", "main");

        assertThat(problems).singleElement().asString()
                .contains("possible credential")
                .contains("assigned password/api_key/secret/token literal");
    }

    @Test
    void aCredentialAddedThenRemovedIsStillRefused() {
        // The case that decides how the scan is built. A net diff of these two
        // commits is empty, but the credential is in the history that gets
        // pushed - so scanning a squashed diff would clear it.
        BranchChange twoCommits = new BranchChange(
                List.of("aaaaaaaa1111", "bbbbbbbb2222"),
                List.of(new ChangedFile("src/Main.java", "modified")),
                List.of(
                        added("String token = \"leaked-value-here\";"),
                        "diff --git a/x b/x\n--- a/x\n+++ b/x\n-String token = \"leaked-value-here\";\n"));

        List<String> problems = inspector.inspect(twoCommits, "petri/1-thing", "main");

        assertThat(problems).singleElement().asString().contains("aaaaaaaa");
    }

    @Test
    void removingACredentialIsAllowed() {
        // Otherwise cleaning up a leak is impossible: the fix would be refused
        // for containing the very thing it deletes.
        BranchChange removal = new BranchChange(
                List.of("cccccccc3333"),
                List.of(new ChangedFile("src/Main.java", "modified")),
                List.of("diff --git a/x b/x\n--- a/x\n+++ b/x\n-String token = \"leaked-value-here\";\n"));

        assertThat(inspector.inspect(removal, "petri/1-thing", "main")).isEmpty();
    }

    @Test
    void theMatchedSecretIsNeverRepeatedInTheMessage() {
        // The message reaches a card, the logs and a pull request body. A leak
        // detector that prints the leak has moved it, not caught it.
        List<String> problems = inspector.inspect(
                change(List.of("src/Main.java"), added("password = \"correct-horse-battery\";")),
                "petri/1-thing", "main");

        assertThat(problems).isNotEmpty();
        assertThat(problems.toString()).doesNotContain("correct-horse-battery");
    }

    @Test
    void privateKeysAndSkKeysAreRecognised() {
        assertThat(inspector.inspect(
                change(List.of("id_rsa"), added("-----BEGIN RSA PRIVATE KEY-----")),
                "petri/1-thing", "main"))
                .anySatisfy(p -> assertThat(p).contains("private key header"));

        assertThat(inspector.inspect(
                change(List.of("src/Main.java"), added("sk-abcdefghijklmnopqrstuvwxyz0123")),
                "petri/1-thing", "main"))
                .anySatisfy(p -> assertThat(p).contains("sk- style API key"));
    }

    @Test
    void protectedPathsAreRefused() {
        assertThat(inspector.inspect(
                change(List.of(".github/workflows/ci.yml"), added("ok")), "petri/1-thing", "main"))
                .anySatisfy(p -> assertThat(p).contains("protected paths"));

        // ** has to cross directories, or a nested Dockerfile slips past.
        assertThat(inspector.inspect(
                change(List.of("deploy/docker/Dockerfile"), added("ok")), "petri/1-thing", "main"))
                .anySatisfy(p -> assertThat(p).contains("protected paths"));

        // And a bare one at the root has to match too.
        assertThat(inspector.inspect(
                change(List.of("Dockerfile"), added("ok")), "petri/1-thing", "main"))
                .anySatisfy(p -> assertThat(p).contains("protected paths"));
    }

    @Test
    void anOrdinaryPathThatMerelyResemblesOneIsAllowed() {
        assertThat(inspector.inspect(
                change(List.of("src/DockerfileParser.java"), added("ok")), "petri/1-thing", "main"))
                .isEmpty();
    }

    @Test
    void theDefaultBranchIsRefused() {
        assertThat(inspector.inspect(change(List.of("x"), added("ok")), "main", "main"))
                .anySatisfy(p -> assertThat(p).contains("default branch"));
    }

    @Test
    void aBranchOutsideThePrefixIsRefused() {
        assertThat(inspector.inspect(change(List.of("x"), added("ok")), "feature/thing", "main"))
                .anySatisfy(p -> assertThat(p).contains("does not start with petri/"));
    }

    @Test
    void anEmptyChangeIsRefused() {
        BranchChange nothing = new BranchChange(List.of(), List.of(), List.of());

        assertThat(inspector.inspect(nothing, "petri/1-thing", "main"))
                .anySatisfy(p -> assertThat(p).contains("nothing was committed"));
    }

    @Test
    void aDiffHeaderIsNotMistakenForAnAddedLine() {
        // "+++ b/config.yml" starts with + but is a header, not content.
        String patch = "diff --git a/config.yml b/config.yml\n"
                + "--- a/config.yml\n+++ b/config.yml\n+setting: value\n";

        assertThat(inspector.inspect(
                new BranchChange(List.of("dddddddd4444"),
                        List.of(new ChangedFile("config.yml", "modified")), List.of(patch)),
                "petri/1-thing", "main"))
                .isEmpty();
    }
}
