-- Refresh Token 교체(Rotation)와 재사용 감지. 비교와 교체를 한 번에 처리해 동시 요청에도 결과가 하나로 정해진다.
-- KEYS[1] 제출된 토큰 키, KEYS[2] 새 토큰 키
-- ARGV[1] 현재 시각(ms), ARGV[2] 재사용 유예(ms), ARGV[3] 새 토큰 수명(ms), ARGV[4] 새 토큰 해시
-- ARGV[5] 회원별 토큰 목록 키 접두어, ARGV[6] 토큰 키 접두어
-- 반환: 'ROTATED:{memberId}' | 'GRACE:{memberId}' | 'REUSED:{memberId}' | 'INVALID'
local record = redis.call('HMGET', KEYS[1], 'memberId', 'status', 'rotatedAt')
local memberId = record[1]
if not memberId then
    return 'INVALID'
end

local now = tonumber(ARGV[1])
local memberTokensKey = ARGV[5] .. memberId

if record[2] == 'ACTIVE' then
    -- 교체된 토큰은 남은 수명 동안 보관해 재사용을 감지한다 (HSET은 TTL을 유지한다).
    redis.call('HSET', KEYS[1], 'status', 'ROTATED', 'rotatedAt', ARGV[1])
    redis.call('HSET', KEYS[2], 'memberId', memberId, 'status', 'ACTIVE')
    redis.call('PEXPIRE', KEYS[2], ARGV[3])
    redis.call('SADD', memberTokensKey, ARGV[4])
    redis.call('PEXPIRE', memberTokensKey, ARGV[3])
    return 'ROTATED:' .. memberId
end

if record[2] == 'ROTATED' and now - tonumber(record[3]) <= tonumber(ARGV[2]) then
    -- 방금 교체된 토큰: 다른 탭·동시 요청으로 보고 Access Token만 새로 준다.
    return 'GRACE:' .. memberId
end

-- 유예가 지난 뒤 교체된 토큰을 다시 쓰면 탈취로 보고 회원의 Refresh Token을 모두 폐기한다.
local tokenHashes = redis.call('SMEMBERS', memberTokensKey)
for _, tokenHash in ipairs(tokenHashes) do
    redis.call('DEL', ARGV[6] .. tokenHash)
end
redis.call('DEL', memberTokensKey)
redis.call('DEL', KEYS[1])
return 'REUSED:' .. memberId
