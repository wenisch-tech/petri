# Access

Two audiences, two mechanisms. People sign in to look at the board; the API
carries a bearer token, because the thing on the other end of it is a script.

```properties
petri.security.username=admin
petri.security.password=...
petri.security.api-key=...
```

Nothing is permissive by default:

- **An unset API key does not disable authentication.** It makes the write API
  reject every request. Petri queues agent runs against real repositories, so an
  unconfigured install should refuse rather than open itself.
- **An unset password generates one per run** and logs it. An install nobody
  configured is inconvenient rather than open.

## The two chains

`/api/**` is stateless and token-only. A signed-in browser cannot authorise a
write: it is authenticated but has no API role, and gets `403`. A caller with no
token or a wrong one gets `401` - unauthenticated and
authenticated-but-not-permitted are different answers, and collapsing them hides
which one a client received.

Everything else requires a session, except:

| Path | Why it is open |
|---|---|
| `/actuator/health/**`, `/actuator/info` | Kubernetes cannot present a session; a probe behind a login form never lets a pod become ready |
| `/actuator/prometheus` | Prometheus scrapes without credentials, and the chart's ServiceMonitor points here |
| `/css/**`, `/webjars/**` | The login page has to be able to style itself |

!!! warning "Keep metrics off any public ingress"
    `/actuator/prometheus` is unauthenticated by necessity. It exposes counts and
    timings rather than card content, but it should still only be reachable from
    inside the cluster.

## Using the API

```bash
curl -X POST https://petri.example/api/boards \
  -H "Authorization: Bearer $PETRI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"slug":"controlpanel","name":"Control panel",
       "forge":"FORGEJO","repository":"example/controlpanel"}'
```

Define the pipeline in one call. States reference each other, so adding them
individually would leave a window where `nextOnPass` points at a state that does
not exist yet and a card goes nowhere:

```bash
curl -X PUT https://petri.example/api/boards/controlpanel/states \
  -H "Authorization: Bearer $PETRI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '[{"name":"implement","position":0,"gate":"REPOSITORY",
        "modelAlias":"coding-agent","nextOnPass":"review","nextOnFail":"implement"},
       {"name":"review","position":1,"gate":"LLM_VERDICT",
        "modelAlias":"chatgpt","nextOnPass":"human","nextOnFail":"implement"},
       {"name":"human","position":2,"gate":"HUMAN","publish":true,"nextOnPass":"done"},
       {"name":"done","position":3,"gate":"NONE","terminal":true}]'
```

`publish: true` marks the state that pushes the branch and opens a pull request.
It is explicit rather than implied by being last, so adding a state after it does
not silently change what publishes.

Then add work:

```bash
curl -X POST https://petri.example/api/boards/controlpanel/cards \
  -H "Authorization: Bearer $PETRI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"title":"Bound the turn by silence",
       "description":"Replace the wall-clock timeout. Acceptance criteria: ..."}'
```
