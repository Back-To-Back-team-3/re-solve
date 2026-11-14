CREATE TABLE member_tag_achievements
(
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    member_id         BIGINT       NOT NULL,
    tag_id            BIGINT       NOT NULL COMMENT '태그 ID (외부 참조)',
    difficulty        INT          NOT NULL COMMENT '1 / 2 / 3',
    solved_count      INT          NOT NULL COMMENT 'AC 문제 수 (PRACTICE, COMPLETED만)',
    earned_points     DECIMAL(6, 2) NOT NULL COMMENT '획득 점수 (힌트 사용 AC는 1/2)',
    total_points      DECIMAL(6, 2) NOT NULL COMMENT '총 문항 점수',
    achievement_rate  DECIMAL(6, 2) NOT NULL COMMENT '성취도(%) = 획득 ÷ 총 × 100',
    recalculated_at   DATETIME(6)  NOT NULL COMMENT '마지막 재계산 시각',
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_member_tag_achievements_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT uk_member_tag_achievements_member_tag_difficulty UNIQUE (member_id, tag_id, difficulty),
    INDEX idx_member_tag_achievements_member_rate (member_id, achievement_rate)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
