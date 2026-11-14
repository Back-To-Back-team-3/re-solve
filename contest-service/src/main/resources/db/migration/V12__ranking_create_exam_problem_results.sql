CREATE TABLE exam_problem_results (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    exam_participant_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    last_valid_exam_submission_id BIGINT NULL,
    score DECIMAL(6, 2) NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_exam_problem_results_participant FOREIGN KEY (exam_participant_id) REFERENCES exam_participants (id),
    CONSTRAINT fk_exam_problem_results_submission FOREIGN KEY (last_valid_exam_submission_id) REFERENCES exam_submissions (id),
    CONSTRAINT uk_exam_problem_results_participant_problem UNIQUE (exam_participant_id, problem_id)
);
