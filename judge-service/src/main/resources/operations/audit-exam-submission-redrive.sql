-- 운영자가 원본 이벤트와 완료된 SQS 이동 작업을 대조한 뒤 같은 세션에서 실행한다.
-- 같은 이벤트와 작업 핸들에는 최초 감사 행을 보존한다.
-- 제출 코드나 DLQ 메시지 본문 전체는 감사 로그에 기록하지 않는다. (이벤트 계약 §7.3)
INSERT INTO audit_logs (actor_id, action, target_type, target_id, after_state, reason, created_at)
VALUES (
    CASE
        WHEN @redrive_actor_id > 0 AND @redrive_receipt_id > 0 AND CHAR_LENGTH(TRIM(@redrive_task_handle)) > 0
        THEN @redrive_actor_id
    END,
    'DLQ_REDRIVE',
    'EXAM_SUBMISSION_REQUEST',
    CASE WHEN IS_UUID(@redrive_event_id) THEN @redrive_event_id END,
    JSON_OBJECT('examSubmissionId', CAST(@redrive_receipt_id AS CHAR), 'moveTaskHandle', @redrive_task_handle),
    CASE WHEN CHAR_LENGTH(TRIM(@redrive_reason)) BETWEEN 1 AND 500 THEN TRIM(@redrive_reason) END,
    CONVERT_TZ(UTC_TIMESTAMP(6), '+00:00', '+09:00')
)
ON DUPLICATE KEY UPDATE id = id;
