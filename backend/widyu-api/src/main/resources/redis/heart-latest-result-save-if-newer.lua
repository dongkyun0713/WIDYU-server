-- 최신 심박을 더 늦은 측정일 때만 덮어쓴다(LLD-0090).
-- KEYS[1] = heart_rate_result:{memberId}, KEYS[2] = heart_rate_result (Spring Data 키스페이스 인덱스 셋)
-- ARGV[1] = 새 측정 시각(epoch ms), ARGV[2] = TTL(초), ARGV[3] = memberId, ARGV[4..] = 해시 필드·값 쌍
-- 저장된 비교 필드가 없거나(변경 전 형식) 숫자가 아니면 덮어쓴다.
local stored = tonumber(redis.call('HGET', KEYS[1], 'measuredAtEpochMs'))
if stored and stored >= tonumber(ARGV[1]) then
    return 0
end
redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], unpack(ARGV, 4))
redis.call('EXPIRE', KEYS[1], ARGV[2])
redis.call('SADD', KEYS[2], ARGV[3])
return 1
