ALTER TABLE diagnosis_attempts
    ADD COLUMN cooldown_notified_at DATETIME(6) NULL COMMENT 'NULL 허용: 쿨타임 해제 알림 미발송 (스케줄러 1.3)' AFTER scored_at;
