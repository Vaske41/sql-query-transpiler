SELECT status, COUNT(*), SUM(total) FROM orders
GROUP BY status, region HAVING COUNT(*) > 5 ORDER BY status DESC, region;
