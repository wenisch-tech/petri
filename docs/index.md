# Petri

Petri is a self-hosted orchestrator for AI coding agents.

It is a state machine over work items with a board on top. Each state binds to a
model, a prompt and a gate; a card advances only when its gate passes.

```
planner    → strong reasoning model → plan names files and criteria → implement
implement  → local coding model     → repository gate               → review
review     → independent model      → explicit approval verdict     → human
human      → you                    → approval                      → done
```

Adding a role is a configuration row, not a deployment.

## Why a state machine

`status: in_progress` tells you nothing. A card in `implement`, with a live
session and a timestamp for its last observed event, tells you what is happening
and whether it is still happening.

Three properties follow from modelling the pipeline explicitly:

- **Roles are data.** Which model drains which column is configuration.
- **Gates have a place to live.** Secret scanning, protected paths, tests, an
  independent review and human approval are steps between states, not `if`
  statements scattered through a script.
- **Failure is legible.** A card stuck in `implement` after three failed
  transitions is a capability problem. A card that never left `planner` is a
  specification problem. One `failed` status cannot tell them apart.

## What Petri does not do

Petri never touches git, credentials, or the push gate. Cloning, branching,
secret scanning, protected-path checks, rebasing and pushing stay in the agent
gateway it drives, behind an allowlisted API.

Petri decides *what* happens next and *who* does it. It cannot push.

## Status

Early development. The skeleton, build and release pipeline are in place; the
state machine is being implemented.

See [Installation and Quickstart](quickstart.md) to run it.
