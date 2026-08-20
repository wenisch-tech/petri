# Architecture

Petri is a control plane. It decides what should happen to a work item next and
which model should do it. It does not execute the work itself.

```
                 ┌──────────────────────────────┐
   you ────────► │ Petri                        │
                 │  board, states, gates,       │
                 │  transitions, history        │
                 └──────────────┬───────────────┘
                                │ allowlisted HTTP
                                ▼
                 ┌──────────────────────────────┐
                 │ Agent gateway                │
                 │  checkout, credential,       │
                 │  secret scan, push gate, PR  │
                 └──────────────┬───────────────┘
                                ▼
                          forge (pull request)
```

## The separation, and why it is deliberate

The gateway owns everything dangerous: the repository credential, the working
tree, and the gate that decides whether a change may be pushed. Petri holds no
credential and has no path to `git push`.

This is not only a security boundary. The gate is the part that has been
hardened by real failures - secret scanning across every commit in a range
rather than a squashed diff, protected-path checks, re-running the gate after a
rebase, refusing non-`hermes/*` branches, never force-pushing. Reimplementing
that inside the orchestrator would mean rediscovering those failures.

## Runs are asynchronous

A run is started, not awaited. Petri asks the gateway to begin, records the
session identifier, and then observes.

This is a correction of an earlier design that blocked on a single HTTP request
for the duration of a turn - up to an hour - and therefore could not answer the
question "is it still alive?", because the only channel was busy carrying the
answer.

## Liveness needs two facts

An agent's status alone is not enough. A session reports `busy` while a single
model call runs, and a model call on a contended GPU can legitimately take four
minutes. `busy` therefore distinguishes nothing on its own.

Petri records both the status and the timestamp of the last observed event, so a
card can say "busy, last activity 4 minutes ago". A run that has produced
nothing for long enough is stopped; a run that is emitting events is left alone
however long it has been going.

The equivalent mistake - bounding a turn by wall clock rather than by silence -
killed working runs and reported them as successes.
