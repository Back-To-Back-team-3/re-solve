CREATE TABLE study_recommendations
(
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    member_id    BIGINT        NOT NULL COMMENT '회원 ID (외부 참조)',
    study_id     BIGINT        NOT NULL COMMENT 'FK -> studies.id',
    score        DECIMAL(6, 2) NOT NULL COMMENT '0.00~100.00',
    reasons_json JSON          NOT NULL COMMENT '추천 근거 배열. 검색·정렬 미사용',
    generated_at DATETIME(6)   NOT NULL COMMENT '계산 시각',
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_study_recommendations_study FOREIGN KEY (study_id) REFERENCES studies (id),
    CONSTRAINT uk_study_recommendations_member_study UNIQUE (member_id, study_id),
    INDEX idx_study_recommendations_member_score (member_id, score)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
