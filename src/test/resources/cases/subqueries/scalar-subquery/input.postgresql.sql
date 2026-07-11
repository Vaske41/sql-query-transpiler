SELECT name, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) FROM users u;
