-- Operator-editable policy, as a single overlay row on top of configuration.
--
-- Every column is nullable and null means "not overridden here - use the
-- environment's default". That is what lets a GitOps-managed deployment and
-- an operator's edit through the UI coexist: nothing here can silently mask a
-- setting nobody touched, only one somebody explicitly changed through the
-- Policy screen.
--
-- One row, id fixed at 1. A table rather than a single-value store because
-- that is the shape every other piece of state in Petri already takes, and
-- because it leaves room to keep a history of changes later without a schema
-- change now.
CREATE TABLE petri_settings (
    id                   BIGINT PRIMARY KEY,
    max_concurrent_runs  INTEGER,
    idle_timeout         VARCHAR(32),
    max_duration         VARCHAR(32),
    startup_grace        VARCHAR(32),
    workspace_root       VARCHAR(512),
    branch_prefix        VARCHAR(128),
    protected_paths      VARCHAR
);
