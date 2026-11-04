CREATE TABLE member_problem_statuses (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    is_solved BOOLEAN NOT NULL,
    first_solved_submission_id BIGINT,
    first_solved_at DATETIME(6),
    attempt_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_member_problem_statuses_problem FOREIGN KEY (problem_id) REFERENCES problems(id),
    CONSTRAINT uk_member_problem_statuses_member_problem UNIQUE (member_id, problem_id)
) ENGINE=InnoDB;
