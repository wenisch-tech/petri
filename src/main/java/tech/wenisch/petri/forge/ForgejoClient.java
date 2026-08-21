package tech.wenisch.petri.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import tech.wenisch.petri.entity.Forge;

/**
 * Forgejo, and Gitea, which share an API.
 *
 * <p>Every endpoint here was checked against a running Forgejo. Two findings
 * shaped it:
 *
 * <p>The compare endpoint returns only {@code filename} and {@code status} - no
 * patch text at all, unlike GitHub's. So the diff comes from
 * {@code git/commits/{sha}.diff}, one call per commit.
 *
 * <p>That turns out to be the better source anyway. Scanning per commit rather
 * than one squashed patch is what catches a credential added in one commit and
 * removed in a later one: it cancels out of a net diff while staying in the
 * history that gets pushed.
 *
 * <p>The web routes also serve {@code .diff}, but they answer a token with a 303
 * to the login page - they want a session cookie. Only the API route works.
 */
public class ForgejoClient implements ForgeClient {

    private static final Logger LOG = LoggerFactory.getLogger(ForgejoClient.class);

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    /** Enough to keep a runaway branch from exhausting memory or the reviewer. */
    private static final int MAX_COMMITS = 100;

    private final RestClient client;
    private final String baseUrl;

    public ForgejoClient(RestClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    @Override
    public Forge forge() {
        return Forge.FORGEJO;
    }

    @Override
    public BranchChange change(String repository, String base, String head) {
        // The branch name goes in unencoded: the compare path takes
        // "{base}...{head}" as one segment, and percent-encoding the slash in a
        // branch like petri/12-thing makes it silently return zero commits
        // rather than an error.
        Map<String, Object> compare = get("/repos/" + repository + "/compare/" + base + "..." + head);

        List<String> commits = new ArrayList<>();
        if (compare.get("commits") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> commit && commit.get("sha") != null) {
                    commits.add(commit.get("sha").toString());
                }
            }
        }

        List<ChangedFile> files = new ArrayList<>();
        if (compare.get("files") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> file && file.get("filename") != null) {
                    Object status = file.get("status");
                    files.add(new ChangedFile(
                            file.get("filename").toString(),
                            status == null ? "modified" : status.toString()));
                }
            }
        }

        if (commits.size() > MAX_COMMITS) {
            LOG.warn("Branch {} has {} commits; reading the first {}",
                    head, commits.size(), MAX_COMMITS);
            commits = commits.subList(0, MAX_COMMITS);
        }

        List<String> patches = new ArrayList<>();
        for (String sha : commits) {
            patches.add(commitDiff(repository, sha));
        }

        return new BranchChange(List.copyOf(commits), List.copyOf(files), List.copyOf(patches));
    }

    private String commitDiff(String repository, String sha) {
        try {
            String diff = client.get()
                    .uri("/repos/{repo}/git/commits/{sha}.diff", repository, sha)
                    .retrieve()
                    .body(String.class);
            return diff == null ? "" : diff;
        } catch (RuntimeException ex) {
            // A patch we cannot read is not a patch we can clear. The gate has to
            // see this as a refusal rather than as an empty, harmless diff.
            throw new ForgeException("could not read the diff of commit " + sha, ex);
        }
    }

    @Override
    public PullRequestRef openPullRequest(String repository, String base, String head,
                                          String title, String body) {
        try {
            Map<String, Object> created = client.post()
                    .uri("/repos/{repo}/pulls", repository)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(Map.of("base", base, "head", head, "title", title, "body", body))
                    .retrieve()
                    .body(JSON_MAP);

            if (created == null || created.get("number") == null) {
                throw new ForgeException("the forge opened no pull request for " + head);
            }
            long number = Long.parseLong(created.get("number").toString());
            Object url = created.get("html_url");
            return new PullRequestRef(number, url == null
                    ? baseUrl + "/" + repository + "/pulls/" + number : url.toString());

        } catch (ForgeException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ForgeException("could not open a pull request for " + head, ex);
        }
    }

    @Override
    public void deleteBranch(String repository, String branch) {
        try {
            client.delete()
                    .uri("/repos/{repo}/branches/{branch}", repository, branch)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException ex) {
            // Best effort. A branch that is already gone, or that branch
            // protection refuses to remove, must not turn a decided card into a
            // failed one.
            LOG.warn("Could not delete branch {} on {}: {}", branch, repository, ex.toString());
        }
    }

    @Override
    public String cloneUrl(String repository) {
        return baseUrl + "/" + repository + ".git";
    }

    private Map<String, Object> get(String path) {
        try {
            Map<String, Object> body = client.get().uri(path).retrieve().body(JSON_MAP);
            if (body == null) {
                throw new ForgeException("empty response from " + path);
            }
            return body;
        } catch (ForgeException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ForgeException("forge call failed: " + path, ex);
        }
    }
}
