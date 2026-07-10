SELECT NOW(), COALESCE(nick, name), CHAR_LENGTH(name), UPPER(name), SUBSTRING(name, 1, 3) FROM users;
