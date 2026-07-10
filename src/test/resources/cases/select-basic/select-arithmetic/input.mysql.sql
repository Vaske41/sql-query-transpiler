SELECT price * quantity + 1, -discount FROM order_items
WHERE NOT (price < 0 OR total <> 100) AND price BETWEEN 10 AND 20;
