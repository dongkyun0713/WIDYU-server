local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local retry = 0
for i, key in ipairs(KEYS) do
    redis.call('ZREMRANGEBYSCORE', key, '-inf', now)
    if redis.call('ZCARD', key) >= tonumber(ARGV[i]) then
        local first = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
        retry = math.max(retry, tonumber(first[2]) - now)
    end
end
if retry > 0 then return retry end
for _, key in ipairs(KEYS) do
    redis.call('ZADD', key, now + tonumber(ARGV[3]), ARGV[4])
    redis.call('PEXPIRE', key, ARGV[3])
end
return 0
