# Petri - Agent Orchestrator

[![License: AGPL v3](https://img.shields.io/badge/License-AGPLv3-blue.svg)](LICENSE.md)
[![Container](https://img.shields.io/badge/container-ghcr.io-blue?logo=github)](https://github.com/wenisch-tech/petri/pkgs/container/petri)
[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot)](https://spring.io/projects/spring-boot)

**Petri** is a self-hosted orchestrator for AI coding agents. It is a state machine over work items with a board on top: every state binds to a model, a prompt, and a gate, and a card only advances when its gate passes.

You describe the pipeline as configuration - *planner → implement → review → human* - and Petri drives it, showing you what each agent is doing while it does it.

> **Status: early development.** The design is settled and documented below; the implementation is being built in phases. Nothing here is production-ready yet.

---

## Why this exists

This project was not the plan. It is the result of running agent-driven development for real, on self-hosted infrastructure, and hitting the same wall from two directions.

### What we needed

One sentence: *add a task, have an AI implement it, review the result before it lands.*

Not a chat window. Not a CLI invocation. A durable work item that moves through stages, where different stages can use different models, where a machine checks the work between stages, and where a human sees a pull request at the end rather than a merged commit.

### What we tried first: an existing agent board

We ran a self-hosted agent task board and drove it with our own dispatcher against a shared coding-agent gateway. It worked - cards became pull requests - but every step toward the pipeline we actually wanted ran into the same wall: **the tool's data model did not have the shape.**

Concretely, the board exposed:

```
task.status : inbox | assigned | in_progress | quality_review | done
agent.role  : coder | reviewer | tester | devops | researcher | assistant | agent
columns     : not configurable - no endpoint to define one
```

A fixed five-value status enum, a fixed role list with no `planner`, and no way to add a column. A pipeline whose stages are *your* stages cannot be expressed. You end up encoding roles in tags or free-form metadata, and the board - the entire reason to have a board - shows none of it.

The deeper mismatch was architectural. Its runtime API described agent CLIs it expected to install and run **on its own host**:

```json
{"runtimes": [{"id": "...", "installed": false, "running": false, "version": null}]}
```

Everything read `installed: false`, permanently, because our agents deliberately run somewhere else - in a hardened pod, with the repository credential, isolated from the board. The board wanted to *be* the agent host. We wanted it to be a control plane. Those are different products, and no amount of glue reconciles them.

That is the honest reason for Petri: not that the other tool was bad, but that it was solving a different problem well.

### What we evaluated: OpenHands

Before writing anything, we seriously evaluated migrating to [OpenHands](https://github.com/OpenHands/OpenHands), which is a strong project and does far more than we needed. Two findings stopped it, and both are worth recording because they generalise:

**1. Forge support is not a detail.** OpenHands integrates GitHub, GitLab, Bitbucket and Azure DevOps. Gitea/Forgejo support is an unimplemented feature request. Our repositories are on Forgejo, so the automatic issue-to-pull-request resolver - the exact feature we would have migrated *for* - was the part that would not work. We would have written the forge integration ourselves, which is precisely the glue we were trying to escape.

**2. The hard problems are below the framework.** Local models served through Ollama have well-documented tool-calling failures: the model reasons about which tool to call and then never calls it, or emits tool-call JSON that does not parse. We had already solved this with a buffering adapter. OpenHands uses the same underlying LLM proxy and hits the same wall - there are open issues for exactly this. Migrating would have meant *re-solving a problem we had already solved*, not inheriting a fix.

The lesson: an orchestrator switch does not fix model-layer or infrastructure-layer problems. Know which layer your pain is in before you rewrite.

### What we learned the hard way

Three production failures shaped this design more than any feature list. All three looked like *"the model is too weak"* and none of them were.

**Silence is not death; elapsed time is not progress.** A turn was killed at a flat ten-minute wall-clock timeout while it was actively working. Its longest gaps between events were 278 and 235 seconds - single model calls on a serialised GPU, not stalls. A turn making five such calls legitimately runs past ten minutes. Worse, the timeout was reported to the caller as *success*, so the failure surfaced two steps later as "nothing was committed" - pointing at entirely the wrong thing.

> **Design consequence:** a run is bounded by *silence*, not by wall clock. Petri records a run's status **and** the timestamp of its last observed event, because an agent can be `busy` for five minutes and perfectly healthy - or `busy` for five minutes and hung, and only the last-event time can tell you which.

**Context is a resource, and it must be scoped to the unit of work.** Our session identifier was stored per checkout rather than per branch, so every new card silently *continued the previous card's conversation*. By the fourth unrelated task the context was exhausted, and the agent's first step returned in 20 milliseconds with `reason: "length"` and zero tokens - then burned six minutes achieving nothing. It read exactly like a model that could not do the work.

> **Design consequence:** the unit of work owns its context. In Petri a card's run has its own session, and a state transition is an explicit decision about whether context carries forward.

**Blocking execution is unobservable execution.** The dispatcher held a single HTTP connection open for the duration of a turn - up to an hour - and learned nothing until it returned. There was no channel to ask "are you alive?", because the only channel was busy carrying the answer.

> **Design consequence:** runs start asynchronously and are polled. The agent gateway already exposes session status, the agent's own todo list, a live diff, an event stream, and an abort endpoint. All of that was invisible because the execution model could not surface it.

### Why a state machine, specifically

It would be easy to read the above and build "a board with more columns". That misses the point. The state machine is the design, for four reasons:

**Roles become data, not code.** *"Model X drains the planner column, its output is checked, then it is handed to model Y"* is a configuration row:

| state | model | gate | on pass | on fail |
|---|---|---|---|---|
| `planner` | a strong reasoning model | plan names files and acceptance criteria | `implement` | `planner` |
| `implement` | a local coding model | repository gate: secrets, protected paths, tests, rebase | `review` | `implement` |
| `review` | an independent model | explicit approval verdict | `human` | `implement` |
| `human` | – | you | `done` | `implement` |

Adding a role is a row. Changing which model plans is a row. Neither is a deployment.

**The gates are where correctness lives.** The interesting part of agent-driven development is not generating a diff - models do that easily. It is everything between the diff and the merge: does it contain a credential, does it touch protected paths, do the tests pass, does an independent reviewer approve, did a human look. A state machine gives those checks a *place*. Without one they become `if` statements scattered through a script, which is exactly what we had, and exactly where our bugs were.

**A transition is an audit record.** `status: in_progress` tells you nothing. A transition log tells you which model produced the change, which gate passed it, on which attempt, and how long it took. When an agent does something surprising - and it will - that history is the only way to find out why.

**States make failure legible.** A card sitting in `implement` with three failed transitions is a different problem from a card that never left `planner`. One is a capability problem, the other a specification problem. A single "failed" status cannot distinguish them, and we spent real hours on that confusion.

### What Petri deliberately does not do

**Petri never touches git, credentials, or the push gate.** Cloning, branching, secret scanning, protected-path checks, rebasing and pushing all stay in the agent gateway, behind an allowlisted API. That code is hardened and has earned its scars; reimplementing it here would re-open every bug it already fixed.

Petri is a control plane. It decides *what* should happen next and *who* should do it. It does not hold the repository credential, and it cannot push.

---

## Features

- **Configurable pipeline** - states, the model bound to each, prompts, gates and transitions are data, not code
- **Board UI** - server-rendered, states as columns, no separate frontend build
- **Live run visibility** - per-card session status, the agent's own todo list, live diff, and time since last activity
- **Pluggable gates** - repository gate, independent model verdict, plan-shape validation, human approval
- **Full transition history** - who moved a card, why, on which attempt
- **Multi-forge** - Forgejo first, others behind one interface
- **REST API** with OpenAPI/Swagger UI
- **MCP server** so assistants can query and drive the board
- **Prometheus metrics** via Actuator and Micrometer
- **H2 by default, PostgreSQL on request** - zero-setup locally, real database in production

## Quick Start

### Run with Docker

```bash
docker run -p 8080:8080 ghcr.io/wenisch-tech/petri:latest
```

Starts with an embedded H2 database, no configuration required. Open <http://localhost:8080>.

### Run on Kubernetes with Helm

```bash
helm install petri oci://ghcr.io/wenisch-tech/charts/petri
```

See [`charts/petri/values.yaml`](charts/petri/values.yaml) for the full value reference.

## Configuration

Petri runs on **H2 by default** so it starts with no setup. Point it at PostgreSQL with standard Spring properties, as environment variables or program arguments:

```bash
docker run -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/petri \
  -e SPRING_DATASOURCE_USERNAME=petri \
  -e SPRING_DATASOURCE_PASSWORD=... \
  ghcr.io/wenisch-tech/petri:latest
```

Flyway migrations run automatically against either database.

Full configuration reference: [`docs/`](docs/).

## Documentation

Documentation sources live in [`docs/`](docs/) and are built with MkDocs.

## Development

### Prerequisites

- Java 25+
- Maven 3.8+

### Run from source

```bash
mvn spring-boot:run
```

```bash
mvn verify
```

### Build a JAR

```bash
mvn -B -DskipTests package
```

## License

Licensed under the GNU Affero General Public License v3.0. See [LICENSE.md](LICENSE.md).
