CREATE TABLE exam_problems (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    problem_revision_id BIGINT NOT NULL COMMENT 'problem.problem_revisions.id',
    problem_title VARCHAR(200) NOT NULL,
    revision_number INT NOT NULL,
    score DECIMAL(6, 2) NOT NULL,
    display_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_exam_problems_exam FOREIGN KEY (exam_id) REFERENCES exams (id),
    CONSTRAINT uk_exam_problems_exam_problem UNIQUE (exam_id, problem_id),
    INDEX idx_exam_problems_problem (problem_id)
);
