local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
for _, key in ipairs(KEYS) do
    local expiry = redis.call('ZSCORE', key, ARGV[1])
    if not expiry or tonumber(expiry) <= now then return -1 end
end
for _, key in ipairs(KEYS) do
    if ARGV[2] == 'success' then
        redis.call('ZREM', key, ARGV[1])
    else
        redis.call('ZADD', key, now + tonumber(ARGV[3]), ARGV[1])
        redis.call('PEXPIRE', key, ARGV[3])
    end
end
return 0
