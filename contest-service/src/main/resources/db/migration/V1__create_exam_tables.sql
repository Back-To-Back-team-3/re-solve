-- 기준 문서: 도메인 및 데이터베이스 v1.0 / §4.4.1 시험 (exams),
-- §4.4.2 시험 문제 (exam_problems), §4.4.3 시험 참가자 (exam_participants).
-- 서비스 내부 시험 관계만 FK로 연결하고 외부 서비스 자원은 ID로 보존한다.
-- active_member_id는 DB가 계산한다. 취소된 행은 NULL로 남겨 이력 보존과 재신청을 함께 허용한다.
-- 테이블·컬럼 COMMENT는 DB 도구에서 확인할 업무상 의미를 설명한다.
-- 상태 전이·순번 증가·시간 기록은 후속 서비스의 책임이며 COMMENT가 해당 동작을 실행하지 않는다.
CREATE TABLE exams
(
    id                  BIGINT         NOT NULL AUTO_INCREMENT COMMENT '시험 PK, DB 자동 증가 식별자',
    study_id            BIGINT         NOT NULL COMMENT '소속 스터디 ID, study.studies.id 외부 참조',
    creator_id          BIGINT         NOT NULL COMMENT '개설자 회원 ID, members.id 외부 참조',
    title               VARCHAR(200)   NOT NULL COMMENT '시험 제목',
    mode                VARCHAR(30)    NOT NULL COMMENT '진행 방식: FIXED 고정 시간 / WINDOW 개인 제한 시간',
    starts_at           DATETIME(6)    NOT NULL COMMENT '시험 시작 시간, Asia/Seoul 기준',
    ends_at             DATETIME(6)    NOT NULL COMMENT '시험 예정 종료 시간, 실제 종료 시간 closed_at과 구분',
    duration_minutes    INT            NULL COMMENT 'WINDOW 개인 제한 시간(분), FIXED는 NULL',
    status              VARCHAR(30)    NOT NULL COMMENT '진행 상태: SCHEDULED / IN_PROGRESS / CLOSED / FINALIZED / CANCELED',
    exam_revision       INT            NOT NULL DEFAULT 1 COMMENT '설정 변경 이벤트 순번, 초기 1, 참가 등록으로는 증가하지 않음',
    total_score         DECIMAL(6, 2)  NOT NULL DEFAULT 100.00 COMMENT '문제별 배점 합계(점), 기본 100.00',
    closed_at           DATETIME(6)    NULL COMMENT '실제 시험 종료 시간, 종료 전에는 NULL',
    result_revision     BIGINT         NOT NULL DEFAULT 0 COMMENT '잠정 결과 갱신·최초 확정·재확정 순번, 초기 0, SSE·캐시·이벤트 정렬 기준',
    finalized_at        DATETIME(6)    NULL COMMENT '최초 결과 확정 시간, 확정 전에는 NULL',
    canceled_at         DATETIME(6)    NULL COMMENT '시험 취소 시간, 취소되지 않은 시험은 NULL',
    version             BIGINT         NOT NULL DEFAULT 0 COMMENT 'JPA 낙관적 락 버전, 설정·결과의 업무 순번과 구분',
    created_at          DATETIME(6)    NOT NULL COMMENT '시험 행 생성 시간, JPA 감사에서 설정',
    updated_at          DATETIME(6)    NOT NULL COMMENT '시험 행 마지막 갱신 시간, JPA 감사에서 설정',
    PRIMARY KEY (id),
    INDEX idx_exams_study_starts_at (study_id, starts_at),
    INDEX idx_exams_status_starts_at (status, starts_at),
    INDEX idx_exams_status_ends_at (status, ends_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '시험 설정·진행 상태·결과 확정 정보를 보존한다';

CREATE TABLE exam_problems
(
    id                  BIGINT         NOT NULL AUTO_INCREMENT COMMENT '시험 문제 PK, DB 자동 증가 식별자',
    exam_id             BIGINT         NOT NULL COMMENT '소속 시험 ID, exams.id 서비스 내부 FK',
    problem_id          BIGINT         NOT NULL COMMENT '문제 ID, problem.problems.id 외부 참조',
    problem_revision_id BIGINT         NOT NULL COMMENT '시험에 고정한 회차 ID, problem.problem_revisions.id 외부 참조',
    problem_title       VARCHAR(200)   NOT NULL COMMENT '편입 시 선택한 문제 회차의 제목 스냅샷',
    revision_number     INT            NOT NULL COMMENT '표시용 회차 번호 스냅샷, 회차 식별자 problem_revision_id와 구분',
    score               DECIMAL(6, 2)  NOT NULL COMMENT '해당 문제의 시험 배점(점)',
    display_order       INT            NOT NULL COMMENT '시험 안의 문제 표시 순서, 1부터 연속, 편입 서비스에서 검증',
    created_at          DATETIME(6)    NOT NULL COMMENT '시험 문제 행 생성 시간, JPA 감사에서 설정',
    updated_at          DATETIME(6)    NOT NULL COMMENT '시험 문제 행 마지막 갱신 시간, JPA 감사에서 설정',
    PRIMARY KEY (id),
    CONSTRAINT fk_exam_problems_exam FOREIGN KEY (exam_id) REFERENCES exams (id),
    CONSTRAINT uk_exam_problems_exam_problem UNIQUE (exam_id, problem_id),
    INDEX idx_exam_problems_problem (problem_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '시험에 편입한 문제의 고정 회차·표시 정보·배점을 보존한다';

CREATE TABLE exam_participants
(
    id                      BIGINT         NOT NULL AUTO_INCREMENT COMMENT '시험 참가 PK, DB 자동 증가 식별자',
    exam_id                 BIGINT         NOT NULL COMMENT '소속 시험 ID, exams.id 서비스 내부 FK',
    member_id               BIGINT         NOT NULL COMMENT '참가 회원 ID, members.id 외부 참조',
    status                  VARCHAR(30)    NOT NULL COMMENT '참가 상태: REGISTERED / STARTED / FINISHED / ABSENT / CANCELED',
    active_member_id        BIGINT         GENERATED ALWAYS AS (IF(status <> 'CANCELED', member_id, NULL)) STORED
        COMMENT '취소는 NULL, 그 외는 member_id, 시험별 유일 제약으로 취소 행 보존과 재신청 허용',
    participant_revision    BIGINT         NOT NULL DEFAULT 1 COMMENT '참가 상태 전이 순번, 초기 1, 상태·이력·Outbox와 같은 트랜잭션에서 증가',
    registered_at           DATETIME(6)    NOT NULL COMMENT '현재 참가 행의 등록 시간',
    entered_at              DATETIME(6)    NULL COMMENT '최초 입장 시간, 미입장은 NULL, 탈퇴 차단 판단 기준',
    personal_ends_at        DATETIME(6)    NOT NULL COMMENT '개인 마감 시간, FIXED는 시험 종료 시간, 수동 종료 시 해당 종료 시간',
    finished_at             DATETIME(6)    NULL COMMENT '참가 종료 시간, 종료 시간이 기록되기 전에는 NULL',
    total_score             DECIMAL(6, 2)  NOT NULL DEFAULT 0.00 COMMENT '문제별 점수의 재계산 합계(점), 초기 0.00',
    last_valid_submitted_at DATETIME(6)    NULL COMMENT '마지막 유효 제출 시간, 제출 없음은 NULL, 동점 정렬 기준',
    auto_submitted_at       DATETIME(6)    NULL COMMENT '자동 제출 완료 시간, 미완료는 NULL, 제출 생성·FINISHED 전이와 같은 트랜잭션에서 기록',
    is_ranked               BOOLEAN        NOT NULL DEFAULT FALSE COMMENT '순위 포함 여부, 한 문제도 제출하지 않은 참가자는 FALSE',
    final_rank              INT            NULL COMMENT '확정 순위, 확정 순위가 없으면 NULL',
    version                 BIGINT         NOT NULL DEFAULT 0 COMMENT '참가자 결과 UPDATE의 JPA 낙관적 락 버전, 참가 상태 순번과 구분',
    created_at              DATETIME(6)    NOT NULL COMMENT '시험 참가 행 생성 시간, JPA 감사에서 설정',
    updated_at              DATETIME(6)    NOT NULL COMMENT '시험 참가 행 마지막 갱신 시간, JPA 감사에서 설정',
    PRIMARY KEY (id),
    CONSTRAINT fk_exam_participants_exam FOREIGN KEY (exam_id) REFERENCES exams (id),
    CONSTRAINT uk_exam_participants_exam_active_member UNIQUE (exam_id, active_member_id),
    INDEX idx_exam_participants_member_status (member_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '회원의 시험 참가·개인 마감·결과를 보존하며 취소 후 재신청은 새 행으로 기록한다';
