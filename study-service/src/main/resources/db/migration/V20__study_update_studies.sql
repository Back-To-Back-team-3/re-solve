-- 구형 스터디 실력 수준을 신규 배정 난이도 상한 체계로 정규화한다(정책 및 상태 §7.1 허용 수준, ERD 7.5 Study).
-- 데이터 보정이므로 updated_at·last_activity_at은 바꾸지 않고, 규칙 밖의 값은 그대로 둔다.
UPDATE studies SET skill_level = 'LV1' WHERE skill_level = 'LV0';
UPDATE studies SET skill_level = 'LV3' WHERE skill_level = 'MASTER';
