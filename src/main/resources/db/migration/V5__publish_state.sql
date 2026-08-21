-- Which state publishes.
--
-- A card that has passed its gates is still only a branch on the gateway until
-- something pushes it and opens a pull request. Marking the state that does so
-- keeps that an explicit part of the pipeline rather than an implicit side
-- effect of reaching the end.
ALTER TABLE workflow_state ADD COLUMN publish BOOLEAN DEFAULT FALSE NOT NULL;
