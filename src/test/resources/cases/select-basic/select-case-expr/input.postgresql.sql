SELECT CASE WHEN qty > 10 THEN 'bulk' WHEN qty > 0 THEN 'few' ELSE 'none' END,
       CASE status WHEN 1 THEN 'ok' ELSE 'bad' END
FROM orders;
