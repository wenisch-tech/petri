-- Baseline migration.
--
-- Deliberately minimal: the domain schema arrives with the state machine in the
-- next phase. This exists so the migration path itself is exercised on both H2
-- and PostgreSQL from the first build, rather than being introduced at the same
-- time as the first real table.
CREATE TABLE petri_schema_marker (
    id          INTEGER      NOT NULL PRIMARY KEY,
    description VARCHAR(255) NOT NULL
);

INSERT INTO petri_schema_marker (id, description) VALUES (1, 'baseline');
