-- What the agent actually said, kept on the run.
--
-- Gates need it: a plan can only be checked for shape if the plan itself is
-- available, and a reviewer needs the words as well as the diff. Fetching it
-- once when the run ends beats asking the gateway again every time something
-- wants to look.
ALTER TABLE agent_run ADD COLUMN output VARCHAR;
