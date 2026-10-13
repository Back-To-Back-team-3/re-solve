CREATE TABLE setup_probe (
    id BIGINT PRIMARY KEY
);

CREATE TABLE lock_transaction_probe (
    id BIGINT NOT NULL PRIMARY KEY,
    payload VARCHAR(200) NOT NULL
) ENGINE=InnoDB;
