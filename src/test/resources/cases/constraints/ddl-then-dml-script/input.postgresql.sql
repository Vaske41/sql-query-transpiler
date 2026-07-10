CREATE TABLE products (id INT NOT NULL PRIMARY KEY, name VARCHAR(50), price DECIMAL(10,2));
INSERT INTO products (id, name, price) VALUES (1, 'Widget', 9.99);
SELECT name FROM products WHERE price > 5;
