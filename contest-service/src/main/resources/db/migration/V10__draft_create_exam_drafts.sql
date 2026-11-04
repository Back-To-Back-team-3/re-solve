CREATE TABLE exam_drafts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    exam_participant_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    language VARCHAR(30) NOT NULL,
    source_code MEDIUMTEXT NOT NULL,
    seq BIGINT NOT NULL,
    saved_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_exam_drafts_participant FOREIGN KEY (exam_participant_id) REFERENCES exam_participants (id),
    CONSTRAINT uk_exam_drafts_participant_problem_language UNIQUE (exam_participant_id, problem_id, language)
);
