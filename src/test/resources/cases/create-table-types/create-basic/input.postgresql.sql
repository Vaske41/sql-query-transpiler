CREATE TABLE users (
    id INTEGER NOT NULL,
    name VARCHAR(100) NOT NULL,
    bio TEXT,
    active BOOLEAN DEFAULT TRUE,
    balance DECIMAL(10,2) DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);
