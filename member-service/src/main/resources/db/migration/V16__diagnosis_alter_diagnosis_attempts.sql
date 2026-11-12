ALTER TABLE diagnosis_attempts
    ADD COLUMN tag_name      VARCHAR(200) NULL COMMENT 'NULL 허용: V16 이전 응시. 시작 시점 태그 이름 (TagLevelChanged 알림 문구용 스냅샷)' AFTER tag_id,
    ADD COLUMN applied_level VARCHAR(30)  NULL COMMENT 'NULL 허용: 채점 전. 진단 반영 후 레벨 max(현재 레벨, result_level)' AFTER result_level;
