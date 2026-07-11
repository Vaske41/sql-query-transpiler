SELECT name FROM products
WHERE category NOT IN ('x', 'y')
  AND name NOT LIKE 'Z%'
  AND price NOT BETWEEN 0 AND 1
  AND deleted_at IS NULL
  AND status != 0
  AND qty % 2 = 0;
