-- M14 ONE-TIME migration for an existing M12 CloudShare D1 database.
-- Do NOT run this after creating a fresh database from the updated schema.sql,
-- because fresh M14 schema already contains these columns.

ALTER TABLE engine_leases
    ADD COLUMN fence_token INTEGER NOT NULL DEFAULT 1;

ALTER TABLE engine_leases
    ADD COLUMN schema_version INTEGER NOT NULL DEFAULT 2;

UPDATE engine_leases
SET schema_version = 2
WHERE schema_version IS NULL OR schema_version < 2;
