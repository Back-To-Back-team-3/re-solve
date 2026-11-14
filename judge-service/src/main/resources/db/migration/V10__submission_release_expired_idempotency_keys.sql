ALTER TABLE submissions DROP CHECK chk_submissions_context_keys;
ALTER TABLE submissions ADD CONSTRAINT chk_submissions_context_keys CHECK (
    (context = 'PRACTICE' AND context_id IS NULL AND source_submission_id IS NULL AND request_hash IS NOT NULL)
    OR (context <> 'PRACTICE' AND context_id IS NOT NULL AND source_submission_id IS NOT NULL
        AND idempotency_key IS NULL AND request_hash IS NULL)
);
