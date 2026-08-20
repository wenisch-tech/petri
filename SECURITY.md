# Security Policy

## Supported versions

Petri is in early development. Security fixes are applied to the latest release
only, published from `main`.

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub Security Advisories](https://github.com/wenisch-tech/petri/security/advisories/new)
rather than opening a public issue.

Include the affected version, reproduction steps, and the impact you believe it
has. You will get an acknowledgement within a few days.

## Scope notes

Petri is a control plane. It holds no repository credential and cannot push to a
repository: cloning, branching, secret scanning, protected-path checks and
pushing are performed by the agent gateway it drives, behind an allowlisted API.

A finding that Petri can be made to *instruct* an agent to do something is in
scope. A finding in the gateway or in the agent runtime belongs to that project.
