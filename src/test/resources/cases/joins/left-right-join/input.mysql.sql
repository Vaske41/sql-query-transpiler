SELECT u.name, o.total FROM users u
LEFT JOIN orders o ON o.user_id = u.id
RIGHT OUTER JOIN payments p ON p.order_id = o.id;
