ALTER TABLE assignment_problems
    ADD COLUMN revision_number INT NULL
        COMMENT '배정 회차 번호 스냅샷. NULL: 컬럼 추가 이전 생성 행'
        AFTER assigned_revision_id;
