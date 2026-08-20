# Petri Helm chart

Deploys [Petri](https://github.com/wenisch-tech/petri), a self-hosted
orchestrator for AI coding agents.

```bash
helm install petri oci://ghcr.io/wenisch-tech/charts/petri
```

## Database

Petri defaults to an embedded H2 database written to `persistence.mountPath`.
With `persistence.enabled=false` that lives in the container's writable layer and
is lost when the pod is replaced - acceptable for evaluation, not for real use.

Either enable persistence:

```yaml
persistence:
  enabled: true
  size: 1Gi
```

or point Petri at PostgreSQL:

```yaml
env:
  SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/petri
  SPRING_DATASOURCE_USERNAME: petri
secrets:
  SPRING_DATASOURCE_PASSWORD: "..."
```

A key set in both `env` and `secrets` takes its value from the Secret.

## Metrics

`/actuator/prometheus` is exposed. Set `metrics.serviceMonitor.enabled=true` if
you run the Prometheus Operator, or `metrics.podAnnotations.enabled=true` for
annotation-based scraping.

See [`values.yaml`](values.yaml) for the full reference.
