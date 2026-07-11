CREATE TABLE users (
    id INT NOT NULL,
    name VARCHAR(100) NOT NULL,
    bio TEXT,
    active TINYINT(1) DEFAULT 1,
    balance DECIMAL(10,2) DEFAULT 0,
    created_at DATETIME DEFAULT NOW()
);
