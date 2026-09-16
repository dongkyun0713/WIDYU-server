if redis.call('EXISTS', KEYS[1]) == 0 then return {'missing'} end
local ttl = redis.call('PTTL', KEYS[1])
if ttl < 0 then return {'unavailable'} end
local failures = tonumber(redis.call('HGET', KEYS[1], 'failures'))
if failures >= 5 then return {'limited', tostring(math.max(1, ttl))} end
if redis.call('HGET', KEYS[1], 'code') ~= ARGV[1] then
    failures = redis.call('HINCRBY', KEYS[1], 'failures', 1)
    if failures >= 5 then return {'limited', tostring(math.max(1, ttl))} end
    return {'mismatch'}
end
local name = redis.call('HGET', KEYS[1], 'name')
if not name then return {'unavailable'} end
redis.call('DEL', KEYS[1])
return {'ok', name}
