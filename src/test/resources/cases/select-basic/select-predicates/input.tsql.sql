SELECT name FROM products
WHERE name LIKE 'A%' AND category IN ('x', 'y') AND deleted_at IS NOT NULL;
