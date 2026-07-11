CREATE TABLE orders (
    id INT NOT NULL,
    user_id INT NOT NULL REFERENCES users (id),
    code VARCHAR(20) UNIQUE,
    PRIMARY KEY (id),
    CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_code UNIQUE (code)
);
