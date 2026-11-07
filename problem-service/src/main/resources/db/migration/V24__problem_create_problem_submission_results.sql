CREATE TABLE problem_submission_results (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    submission_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    judge_attempt INT NOT NULL,
    verdict VARCHAR(30) NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_problem_submission_results_problem FOREIGN KEY (problem_id) REFERENCES problems(id),
    CONSTRAINT uk_problem_submission_results_submission UNIQUE (submission_id),
    INDEX idx_problem_submission_results_member_problem (member_id, problem_id)
) ENGINE=InnoDB;
