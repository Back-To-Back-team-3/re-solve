-- 로그아웃: 본인 Refresh Token 하나를 폐기한다
-- KEYS[1] 토큰 키, KEYS[2] 회원별 토큰 목록 키
-- ARGV[1] 회원 ID, ARGV[2] 토큰 해시
-- 반환: 폐기했으면 1, 없거나 다른 회원의 토큰이면 0
local owner = redis.call('HGET', KEYS[1], 'memberId')
if owner ~= ARGV[1] then
    return 0
end
redis.call('DEL', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[2])
return 1
