local retry = 0
for i, key in ipairs(KEYS) do
    local count = tonumber(redis.call('GET', key) or '0')
    if count >= tonumber(ARGV[i * 2 - 1]) then
        local ttl = redis.call('PTTL', key)
        if ttl < 0 then return -1 end
        retry = math.max(retry, ttl, 1)
    end
end
if retry > 0 then return retry end
for i, key in ipairs(KEYS) do
    local count = redis.call('INCR', key)
    if count == 1 then redis.call('PEXPIRE', key, ARGV[i * 2]) end
end
return 0
