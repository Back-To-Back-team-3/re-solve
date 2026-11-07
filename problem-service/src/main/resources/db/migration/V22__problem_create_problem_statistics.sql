CREATE TABLE problem_statistics (
    problem_id BIGINT NOT NULL PRIMARY KEY,
    submission_count INT NOT NULL,
    accepted_submission_count INT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_problem_statistics_problem FOREIGN KEY (problem_id) REFERENCES problems(id)
) ENGINE=InnoDB;
