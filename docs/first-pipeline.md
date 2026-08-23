# Your First Pipeline

This assumes Petri is already running - see [Installation and
Quickstart](quickstart.md) if it is not - and that you already have somewhere
for it to talk to:

- **A forge.** Forgejo, reachable over HTTP(S), with a personal access token.
- **An agent gateway.** opencode's own session API (`opencode serve`, or
  whatever fronts it), reachable over HTTP(S).

Wherever this guide says `https://forge.example.com` or
`http://gateway.example:4096`, substitute your own. Nothing below invents a
value for either.

## What has to be set before you sign in

One thing cannot be configured from the browser, by design:

```properties
petri.security.password=...
```

This is the board login. Leave it unset and Petri generates one at startup and
logs it, which is fine for a first look but worth pinning down for anything
that stays up. Everything in this guide - creating the board, defining its
pipeline, adding a card - is done signed in as `admin` with this password, no
API key required.

If you later want to script against Petri instead - creating boards or cards
from a pipeline of your own, say - set `petri.security.api-key` too and use
`PUT /api/boards/{slug}/states` and friends directly; see
[Access and the API](configuration-security.md). Nothing in this guide needs
that.

If your gateway sits behind HTTP basic auth, its password is the same kind of
browser-proof exception:

```properties
petri.gateway.password=...
```

This one has no field on the Connections screen at all, ever - it stays only
in whatever holds it today, this property or a mounted Secret. Everything else
below - the gateway's URL, the forge, the reviewing model - you set from the
browser in a moment, and none of it needs a redeploy to change later.

## 1. Sign in

Open Petri and sign in as `admin` with whatever `petri.security.password` you
set (or the one logged at startup).

## 2. Connect the agent gateway

**Settings → Connections**, *Agent gateway* card:

| Field | Value |
|---|---|
| Base URL | `http://gateway.example:4096` - wherever opencode's session API answers |
| Username | whatever your basic-auth layer expects, or leave `petri` if there is none |
| Enabled | leave at "(environment default)", which is enabled |

**Save**, then **Test connection**. A working gateway answers with an HTTP
status; anything else - connection refused, a timeout - means the URL or
network path is wrong, and you now know that in five seconds instead of from a
startup log.

## 3. Connect Forgejo

Same page, *Forge* card → **Add a forge**:

| Field | Value |
|---|---|
| Type | `FORGEJO` |
| Base URL | `https://forge.example.com` - the root, not `/api/v1` |
| Token | a personal access token, scope `write:repository` at minimum |

Click **Add**, then **Test connection**. A working forge answers with its
version.

**Deciding who pushes.** The agent needs its own way to reach the forge to
`git push`. The simple path for a first run: leave **Hand a token to the
agent** at its default (off) only if your agent already has its own
credential configured independently; otherwise switch it **On** and save -
Petri will put the token above into the clone URL it hands the agent. For
anything beyond a first run, give the agent a separate, lower-scope token in
the **Agent token** field instead of reusing Petri's own: that one only ever
needs to push, Petri's only ever needs to read and open a pull request.
See [Architecture](architecture.md#credentials) for why this split exists.

## 4. Connect a reviewing model (optional)

If you want an independent model verdict between the agent's diff and a
pull request, fill in the *Reviewing model* card: an OpenAI-compatible
`Base URL` (a LiteLLM proxy, for instance), `Model`, and `API key` if the
endpoint needs one. Skip this and use `HUMAN` or `NONE` gates instead if you'd
rather review everything yourself for now - nothing else in this guide
requires it.

## 5. Create a board

**New board**, in the top navigation - reachable from anywhere, not just the
boards list. Fill in a name, a repository (`owner/name`), the forge you just
connected, and a default branch. The slug fills itself in from the name as you
type; edit it directly if you want something different. It becomes the
board's URL (`/boards/<slug>`) and cannot be changed afterward, which is the
one field worth pausing on before submitting.

**Create and define its pipeline** takes you straight to step 6 - there is
nothing useful to do with a board before it has states. Everything about the
board itself (repository, forge, default branch, whether the runner may pick
up work here) stays editable afterward from its own settings page.

## 6. Define the pipeline

You're already here - creating the board dropped you straight into its
pipeline editor. This is the shape that keeps a credential off the remote
until something has actually checked the diff; build it in this order:

| State | Gate | Model alias | On pass | On fail | Notes |
|---|---|---|---|---|---|
| `implement` | `REPOSITORY` | your opencode model alias, e.g. `coding-agent` | `review` | `implement` | Default prompt: clone, commit, **do not push**, report the diff |
| `review` | `LLM_VERDICT` (or `HUMAN`/`NONE` if you skipped step 4) | same alias | `push` | `implement` | Default prompt re-emits the diff for judgment |
| `push` | `NONE` | same alias | `human` | `push` | **Needs an explicit prompt - see below** |
| `human` | `HUMAN`, **Publish** on | - | `done` | - | Petri opens the pull request on arrival here |
| `done` | `NONE`, **Terminal** on | - | - | - | Nothing more happens |

**The `push` state's prompt is the one field you must fill in by hand.** Its
gate, `NONE`, has no prompt shape of its own, so leaving it blank sends the
agent only the card's title and description - it will not push anything.
Open its "Prompt template" details and paste:

```
The change you committed in this workspace has been reviewed and
approved.

Push branch {{branch}} to the remote. Do not force-push, do not touch
the default branch, and do not open a pull request - Petri opens it.

Reply with the result of the push and nothing else.
```

Every other state's blank prompt already does the right thing for its gate -
see [The prompt contract](configuration-gateway.md#the-prompt-contract) for
why `push` is the exception rather than the rule.

Drag to reorder if you typed them in a different order, then **Save
pipeline**.

## 7. Add a card

Back on the board, **Add card**. Give it a title and a real description -
"fix the bug" produces thirteen steps of an agent hunting through a checkout
for code that was never checked out; "add a `/healthz` endpoint returning 200
with an empty body; acceptance: `curl localhost:8080/healthz` returns 200"
gives it something to actually finish.

## 8. Watch it run

The board refreshes to show the card's status, time since its last event, and
attempt count. Click into it for the full picture: description, transition
history (which model moved it, past which gate, and why), the branch, and -
once `human` is reached - the pull request link.

If a run seems stuck, check **quiet** on its card: that is time since the
agent last did anything, which is what actually bounds a run - not wall-clock
elapsed time. See [Bounding a run](configuration-gateway.md#bounding-a-run).

## What's next

- A board's own settings (repository, forge, default branch, whether the
  runner may pick up work there at all) are at
  `https://petri.example/boards/my-project/settings` - editable the same way
  Connections is, live, no restart.
- Concurrency, the run-bound timeouts, protected paths and the branch prefix
  are all editable at **Settings → Policy** - see
  [Bounding a run](configuration-gateway.md#bounding-a-run) for what each one
  means before changing it.
- Multiple boards work the same way: repeat step 5 with a different slug and
  repository, then give it its own pipeline in step 6. States are per board;
  connections are per instance and shared across every board.
