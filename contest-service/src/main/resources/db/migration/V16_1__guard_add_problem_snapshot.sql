ALTER TABLE contest_resource_guards
    ADD COLUMN source_revision_id BIGINT NULL,
    ADD COLUMN source_revision_number INT NULL,
    ADD COLUMN source_supported_languages JSON NULL;
