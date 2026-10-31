ALTER TABLE judge_validation_runs
    DROP CHECK chk_validation_runs_success,
    DROP CHECK chk_validation_runs_verdict,
    DROP COLUMN verdict,
    ADD CONSTRAINT chk_validation_runs_success CHECK (result <> 'SUCCEEDED' OR runner_version IS NOT NULL);
