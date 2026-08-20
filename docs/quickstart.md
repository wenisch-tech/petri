# Installation and Quickstart

## Docker

```bash
docker run -p 8080:8080 ghcr.io/wenisch-tech/petri:latest
```

Petri starts with an embedded H2 database and no configuration. Open
<http://localhost:8080>.

To keep data across restarts, mount a volume at `/app/data`:

```bash
docker run -p 8080:8080 -v petri-data:/app/data ghcr.io/wenisch-tech/petri:latest
```

## Kubernetes

```bash
helm install petri oci://ghcr.io/wenisch-tech/charts/petri
```

The chart defaults to the embedded database with persistence disabled, which is
suitable for evaluation only. For anything you care about, either enable
`persistence` or point Petri at PostgreSQL - see
[Database](configuration-database.md).

## From source

Requires Java 25+ and Maven 3.8+.

```bash
mvn spring-boot:run
```

```bash
mvn verify
```

`mvn verify` runs the tests and the JaCoCo coverage check, which is the same
gate CI applies.

## Endpoints

| Path | Purpose |
|---|---|
| `/` | Board |
| `/swagger-ui.html` | API documentation |
| `/actuator/health` | Health, including liveness and readiness probes |
| `/actuator/prometheus` | Metrics |
| `/h2-console` | H2 console, when the embedded database is in use |
