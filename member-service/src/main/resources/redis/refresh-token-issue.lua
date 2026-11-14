-- Refresh Token 저장 (로그인 1회 = 세션 1개)
-- KEYS[1] 토큰 키, KEYS[2] 회원별 토큰 목록 키, KEYS[3] 세션별 토큰 목록 키
-- ARGV[1] 회원 ID, ARGV[2] 수명(ms), ARGV[3] 토큰 해시, ARGV[4] 세션 ID
redis.call('HSET', KEYS[1], 'memberId', ARGV[1], 'status', 'ACTIVE', 'sid', ARGV[4])
redis.call('PEXPIRE', KEYS[1], ARGV[2])
redis.call('SADD', KEYS[2], ARGV[3])
redis.call('PEXPIRE', KEYS[2], ARGV[2])
redis.call('SADD', KEYS[3], ARGV[3])
redis.call('PEXPIRE', KEYS[3], ARGV[2])
return 1
