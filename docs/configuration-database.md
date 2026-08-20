# Database

Petri runs on **H2 by default** so it starts with no setup, and on
**PostgreSQL** when you point it at one. Flyway owns the schema in both cases, so
switching changes nothing else.

## H2 (default)

```properties
spring.datasource.url=jdbc:h2:file:./data/petri;AUTO_SERVER=TRUE
spring.datasource.username=sa
spring.datasource.password=
```

The database is a file under `./data`. In a container that is `/app/data`, which
is where the Helm chart mounts its volume when `persistence.enabled` is true.

!!! warning "H2 without persistence is disposable"
    With no volume mounted, the database lives in the container's writable layer
    and disappears when the container is replaced. That is fine for evaluation
    and wrong for anything else.

## PostgreSQL

Override the standard Spring datasource properties. As environment variables:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/petri
SPRING_DATASOURCE_USERNAME=petri
SPRING_DATASOURCE_PASSWORD=...
```

Or as program arguments:

```bash
java -jar petri.jar --spring.datasource.url=jdbc:postgresql://db:5432/petri
```

In the Helm chart, set them under `env`, and put the password in `secrets` so it
is held in a Kubernetes Secret rather than in the ConfigMap:

```yaml
env:
  SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/petri
  SPRING_DATASOURCE_USERNAME: petri
secrets:
  SPRING_DATASOURCE_PASSWORD: "..."
```

A key present in both `env` and `secrets` takes its value from the Secret.

## Migrations

Flyway runs automatically at startup and is the single source of truth for
schema. Hibernate is configured with `ddl-auto=validate` and will never alter a
table: if the schema and the entities disagree, Petri fails to start rather than
silently migrating your data.
