-- The baseline marker existed only so the migration path was exercised before
-- there was a real schema. V2 supersedes it. V1 is left untouched: an applied
-- migration is immutable, so it is dropped forward rather than edited.
DROP TABLE petri_schema_marker;
