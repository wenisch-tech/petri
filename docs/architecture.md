# Architecture

Petri is a control plane. It decides what should happen to a work item next and
which model should do it. It does not execute the work itself, and it does not
run git.

```
                 ┌──────────────────────────────┐
   you ────────► │ Petri                        │
                 │  board, states, gates,       │
                 │  transitions, history        │
                 └───────┬──────────────┬───────┘
                         │ prompt       │ read, gate,
                         │ + observe    │ open the PR
                         ▼              │
          ┌──────────────────────────┐  │
          │ Agent runtime            │  │
          │  workspace per card,     │  │
          │  its own git credential  │  │
          └──────────────┬───────────┘  │
                         │ clone, commit, push
                         ▼              ▼
                   ┌──────────────────────────┐
                   │ forge                    │
                   └──────────────────────────┘
```

## Petri owns no git, and shares no filesystem

The agent clones, commits and pushes. Petri names a workspace, sends a prompt,
watches the session, and reads what landed from the forge's API.

This is a deliberate correction. An earlier design put a bespoke shim between the
two, holding the credential and the checkout - which worked, and which nobody
else could run: it tied the orchestrator to one agent runtime on one machine with
one shared disk. Everything Petri now knows about a result it learns either from
the forge or from what the agent said, and both are available over HTTP from
anywhere.

The agent interface is four methods and none of them mention git: start a turn,
observe sessions, abort a session, read the last message.

## The order is the safety argument

1. The implementing turn clones, works, **commits**, and reports the complete
   diff in a fenced block. It is told not to push.
2. Petri inspects that reported diff: branch discipline, protected paths, and a
   secret scan **per commit** rather than on a squashed range - a credential
   added in one commit and removed in a later one cancels out of a net diff while
   staying in the history that would be pushed.
3. A reviewing model gets a verdict gate on the same change. A gate that cannot
   decide **holds**; it never passes on an error.
4. Only then is a pushing turn asked for.
5. Petri reads the branch back from the forge, inspects it **again** - what
   landed may not be what was reported, and the first check cannot see that by
   construction - and opens the pull request itself.

Nothing reaches the remote before the secret scan. Once a branch is pushed, a
credential is in its history whatever anyone decides afterwards; deleting the
branch does not undo it.

## Why Petri opens the pull request, not the agent

Four reasons, all of which showed up in practice:

- a rejected change never gets one at all;
- the body can say which gates passed and which models were involved, which the
  agent does not know;
- a card that comes round a second time does not open a second pull request;
- its existence is a fact Petri established rather than a claim it has to
  believe.

Petri never merges. Landing a change is a person's decision.

## Credentials

Petri's own forge token needs to read a branch and open a pull request. It does
not need to push, and by default it never does anything else.

Whether the *agent* gets a credential from Petri is a switch, off by default. Off,
the agent brings its own, arranged wherever it runs, and Petri never sees it. On,
Petri hands one over in the clone URL - convenient, and strictly worse, because
the secret then reaches the agent's disk and its logs. Give it a separate token
when you turn it on: that one may push, Petri's need not.

Because handover puts a secret somewhere the agent can quote it, everything the
agent says is redacted - literal and percent-encoded - before it is stored on a
run or rendered on a card.

## Runs are asynchronous

A run is started, not awaited. Petri asks the runtime to begin, records the
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

## No transaction is held across a network call

One liveness cycle makes four kinds of remote call: observing sessions, reading a
transcript, asking a reviewing model for a verdict, and opening a pull request. A
review alone can take minutes.

Every database write is its own short transaction, and the poller works from
flattened records rather than entities so nothing lazy is touched outside a
session. Holding one transaction across the whole cycle pins a connection for as
long as a model takes to answer - merely wasteful at one card at a time, and pool
exhaustion the moment cards run concurrently.
