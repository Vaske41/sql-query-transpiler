SELECT a.x FROM a JOIN b ON a.id = b.a_id JOIN c ON b.id = c.b_id WHERE c.flag = 1;
