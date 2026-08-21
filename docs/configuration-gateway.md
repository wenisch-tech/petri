# Agent gateway

Petri does not run agents, and it does not run git. It sends a prompt to an agent
runtime, watches the session, reads the result from the forge, and decides.

That is the whole coupling. Petri and the agent do not share a filesystem, so
they need not share a machine. Anything that can run a shell, hold a credential
and answer four HTTP calls can sit behind this interface.

The runner is **enabled by default** and idle until you give it a gateway.

```properties
petri.gateway.base-url=http://gateway.internal:4096
petri.gateway.username=petri
petri.gateway.password=...
```

With no `base-url`, Petri logs a warning at startup and starts nothing. That is a
configuration mistake rather than a mode, so it is reported as one.

To pause the runner without discarding the configuration - during maintenance,
say - set:

```properties
petri.gateway.enabled=false
```

## What Petri asks the gateway for

| Purpose | Call |
|---|---|
| Start a turn, asynchronously | `POST /session?directory=…` then `POST /session/{id}/prompt_async` |
| Observe every open session at once | `GET /session/status` |
| Stop a session | `POST /session/{id}/abort` |
| Read what the agent said | `GET /session/{id}/message` |

Starting is asynchronous by design. An orchestrator that blocks for the length of
a turn cannot answer "is it still alive?", because the only channel it has is
busy carrying the answer.

Observation is one call for every open session rather than one call each. A poll
whose cost grows with the board eventually stops running often enough to be
liveness at all.

!!! note "`GET /session/status` lists only busy sessions"
    A session that is not currently doing anything is simply **absent** from the
    response. Petri reads absence as idle, not as gone - the opposite reading
    ends every run within seconds of creating it. A freshly started run is also
    invisible until it produces its first output, which is what
    `petri.gateway.startup-grace` covers.

## Workspaces

```properties
petri.workspace-root=/workspaces/petri
petri.max-concurrent-runs=1
```

Petri names one workspace per card - `/workspaces/petri/card-42` - and passes it
as the session directory. It never opens it: the path exists on the agent's
filesystem.

One per **card**, not per repository. Turns on the same card share a workspace on
purpose, so the state that commits and the state that pushes are looking at the
same tree. Two cards on one repository never do, because sharing one checkout is
what forces work to run strictly one at a time, and what lets two tasks inherit
each other's session context until it runs out.

`max-concurrent-runs` stays at one by default anyway, for a different reason:
where every model call serialises on a single GPU, a second turn spends its time
queued and silent - and silence is exactly what gets a run aborted. Raise it only
where turns genuinely run in parallel.

## Bounding a run

```properties
petri.gateway.idle-timeout=PT15M
petri.gateway.max-duration=PT1H
petri.gateway.request-timeout=PT30S
petri.gateway.startup-grace=PT2M
```

`idle-timeout` is the bound that matters, and it measures **silence**, not age.

A gateway reports a session as busy for the whole of a single model call, and one
such call on a contended GPU has been measured at 278 seconds. A turn making
several of them legitimately runs for many minutes while remaining perfectly
healthy. Bounding by elapsed time kills that run - and, if the timeout is not
propagated properly, reports it as a success, so the failure surfaces later as
something unrelated.

So a run is stopped when it has produced **nothing at all** for `idle-timeout`.

`max-duration` is a ceiling for a run that keeps emitting but never finishes; it
should stay comfortably above `idle-timeout` or silence stops being the deciding
signal. `request-timeout` bounds a single HTTP call and has nothing to do with
how long a turn may take - without it, a gateway that accepts a connection and
then stops answering would wedge the poller that exists to detect exactly that.

## The prompt contract

Petri drives the agent with instructions, not with an API. A state that has no
prompt of its own gets a default chosen by its gate, and the shape of those
defaults is the safety argument:

- an **implementing** state (repository gate) is told to clone, work, commit,
  **not push**, and finish by reporting the complete diff in a ` ```diff ` block;
- a **reviewing** state (verdict gate) is told to re-emit that diff and change
  nothing;
- a **pushing** state has to be given its prompt explicitly, because its gate
  says nothing about pushing and inferring "push" from "no gate" would push from
  every ungated state;
- Petri opens the pull request itself, from what actually landed.

Nothing reaches the remote before the secret scan has read the diff. Once a
branch is pushed, a credential is in its history whatever anyone decides
afterwards - a deleted branch does not undo that.

The contract is written as instructions because no two agent runtimes enforce the
same way. What actually constrains the agent is the scope of its token and branch
protection on the forge. See [Architecture](architecture.md) for what happens
after the diff comes back.
