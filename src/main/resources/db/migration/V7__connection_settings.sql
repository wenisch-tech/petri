-- Operator-editable connection details, overlaid on environment configuration -
-- the same shape petri_settings already takes for policy, and the same rule:
-- every column nullable, null meaning "not overridden here".
--
-- The gateway password has no column, anywhere, by decision: it stays only in
-- whatever holds it today - an environment variable, a mounted Secret - never
-- in this database.
CREATE TABLE connection_settings (
    id               BIGINT PRIMARY KEY,
    gateway_base_url VARCHAR,
    gateway_username VARCHAR,
    gateway_enabled  BOOLEAN,
    review_base_url  VARCHAR,
    review_api_key   VARCHAR,
    review_model     VARCHAR
);

-- One row per forge type that has ever been configured through the UI. A
-- forge can exist here with no matching environment entry at all - adding one
-- from a fresh install with nothing in petri.forge.* is meant to work.
CREATE TABLE forge_connection_settings (
    forge               VARCHAR(32) PRIMARY KEY,
    base_url            VARCHAR,
    token               VARCHAR,
    hand_token_to_agent BOOLEAN,
    agent_token         VARCHAR,
    agent_username      VARCHAR
);
