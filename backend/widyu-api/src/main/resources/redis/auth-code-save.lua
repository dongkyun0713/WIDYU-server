redis.call('HSET', KEYS[1], 'code', ARGV[1], 'name', ARGV[2], 'failures', 0, 'id', ARGV[4])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return 1
