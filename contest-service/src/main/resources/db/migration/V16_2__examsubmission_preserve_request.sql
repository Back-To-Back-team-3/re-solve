ALTER TABLE exam_submissions ADD COLUMN request_snapshot JSON NULL AFTER source_code;

UPDATE exam_submissions s
JOIN exam_participants p ON p.id = s.exam_participant_id AND p.exam_id = s.exam_id
JOIN exam_problems ep ON ep.exam_id = s.exam_id AND ep.problem_id = s.problem_id
JOIN (
    SELECT outbox_events.*, COUNT(*) OVER (PARTITION BY aggregate_id) AS original_count
    FROM outbox_events
    WHERE BINARY event_type = BINARY 'ExamSubmissionRequested'
        AND BINARY aggregate_type = BINARY 'EXAM_SUBMISSION'
) o ON o.aggregate_id = CAST(s.id AS CHAR) AND o.original_count = 1
SET s.request_snapshot = o.payload
WHERE s.request_snapshot IS NULL
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.eventId')) = BINARY o.event_id
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.eventType')) = BINARY o.event_type
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.schemaVersion')) = BINARY o.schema_version
    AND BINARY o.schema_version = BINARY 'v1'
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.aggregateId')) = BINARY o.aggregate_id
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.correlationId')) = BINARY o.correlation_id
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.occurredAt'))
        = BINARY DATE_FORMAT(o.occurred_at, '%Y-%m-%dT%H:%i:%s.%f+09:00')
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.examId')) = BINARY CAST(s.exam_id AS CHAR)
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.examSubmissionId')) = BINARY CAST(s.id AS CHAR)
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.participantId')) = BINARY CAST(s.exam_participant_id AS CHAR)
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.userId')) = BINARY CAST(p.member_id AS CHAR)
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.problemId')) = BINARY CAST(s.problem_id AS CHAR)
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.problemRevisionId')) = BINARY CAST(ep.problem_revision_id AS CHAR)
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.draftSeq')) = BINARY CAST(s.draft_seq AS CHAR)
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.submissionType')) = BINARY s.submission_type
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.language')) = BINARY CASE s.language
        WHEN 'JAVA' THEN 'java' WHEN 'PYTHON' THEN 'python3' WHEN 'CPP' THEN 'cpp' ELSE '' END
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.sourceCode')) = BINARY s.source_code
    AND BINARY JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.receivedAt'))
        = BINARY DATE_FORMAT(s.received_at, '%Y-%m-%dT%H:%i:%s.%f+09:00');
