-- 로그아웃: 세션(sid)에 속한 Refresh Token을 모두 폐기한다.
-- 교체 전(ROTATED)·후(ACTIVE) 토큰이 함께 폐기되므로, 로그아웃 뒤에는 유예 시간 안의 이전 토큰으로도 재발급할 수 없다.
-- 재발급과 로그아웃이 동시에 오면: 재발급이 먼저면 새 토큰도 같은 세션에 들어가 함께 폐기되고, 로그아웃이 먼저면 재발급이 INVALID가 된다.
-- KEYS[1] 쿠키로 받은 토큰 키 (없으면 빈 토큰의 키), KEYS[2] 회원별 토큰 목록 키
-- ARGV[1] 회원 ID, ARGV[2] Access Token의 세션 ID ('' 이면 없음), ARGV[3] 세션별 토큰 목록 키 접두어, ARGV[4] 토큰 키 접두어
-- 반환: 폐기한 토큰 수
local memberId = ARGV[1]
local sessionIds = {}
if ARGV[2] ~= '' then
    sessionIds[#sessionIds + 1] = ARGV[2]
end

local revoked = 0
local owner = redis.call('HGET', KEYS[1], 'memberId')
if owner == memberId then
    local cookieSid = redis.call('HGET', KEYS[1], 'sid')
    if cookieSid and cookieSid ~= '' and cookieSid ~= ARGV[2] then
        sessionIds[#sessionIds + 1] = cookieSid
    end
    redis.call('DEL', KEYS[1])
    redis.call('SREM', KEYS[2], string.sub(KEYS[1], string.len(ARGV[4]) + 1))
    revoked = revoked + 1
end

for _, sid in ipairs(sessionIds) do
    local sessionKey = ARGV[3] .. sid
    for _, tokenHash in ipairs(redis.call('SMEMBERS', sessionKey)) do
        local tokenKey = ARGV[4] .. tokenHash
        if redis.call('HGET', tokenKey, 'memberId') == memberId then
            redis.call('DEL', tokenKey)
            redis.call('SREM', KEYS[2], tokenHash)
            revoked = revoked + 1
        end
    end
    redis.call('DEL', sessionKey)
end
return revoked
