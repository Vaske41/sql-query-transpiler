SELECT GETDATE(), ISNULL(nick, name), LEN(name), UPPER(name), SUBSTRING(name, 1, 3) FROM users;
