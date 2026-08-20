# Agent gateway

Petri does not run agents. It drives a gateway that owns the checkout, the
repository credential and the push gate, and it observes what happens.

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

## Bounding a run

```properties
petri.gateway.idle-timeout=PT15M
petri.gateway.max-duration=PT1H
petri.gateway.request-timeout=PT30S
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

## What Petri asks the gateway for

| Purpose | Call |
|---|---|
| Start a turn, asynchronously | `POST /run/async` returning a session id |
| Observe every open session at once | `GET /session/status` |
| Stop a session | `POST /session/{id}/abort` |

Starting is asynchronous by design. An orchestrator that blocks for the length of
a turn cannot answer "is it still alive?", because the only channel it has is
busy carrying the answer.

!!! warning "Not yet validated against a live gateway"
    The HTTP client is written against this contract but has so far only been
    exercised against a fake. Reconcile the endpoint shapes with your gateway
    before relying on it.
