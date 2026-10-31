package com.backtoback.judge.submission.store;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;

import lombok.RequiredArgsConstructor;

/** Redis 서버 시각으로 두 제출 한도를 원자적으로 확인한다. (정책 및 상태 §3.1) */
@Component
@RequiredArgsConstructor
public class SubmissionRateLimiter {
    // 같은 회원 키는 동일 슬롯을 사용하고, 거절된 요청은 어느 한도도 소비하지 않는다.
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
        local time = redis.call('TIME')
        local now = time[1] * 1000 + math.floor(time[2] / 1000)
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - 60000)
        local wait = redis.call('PTTL', KEYS[2])
        if redis.call('ZCARD', KEYS[1]) >= 10 then
            local first = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            wait = math.max(wait, tonumber(first[2]) + 60000 - now)
        end
        if wait > 0 then return math.ceil(wait / 1000) end
        local sequence = redis.call('INCR', KEYS[3])
        redis.call('PEXPIRE', KEYS[3], 60000)
        redis.call('ZADD', KEYS[1], now, tostring(now) .. ':' .. tostring(sequence))
        redis.call('PEXPIRE', KEYS[1], 60000)
        redis.call('SET', KEYS[2], '1', 'PX', 5000)
        return 0
        """, Long.class);
    private final StringRedisTemplate redis;

    /**
     * 분당 10회와 동일 문제 5초 간격을 함께 확인하고 허용 시 소비한다.
     * Redis Lua 원자성을 사용하므로 DB 트랜잭션을 시작하지 않는다.
     *
     * @param memberId 인증 회원 ID
     * @param problemId 문제 ID
     * @throws JudgeException {@code SUBMISSION_RATE_LIMITED}: 제출 요청 한도를 초과한 경우; {@code COMMON_DEPENDENCY_UNAVAILABLE}:
     *     Redis 조회가 실패하거나 응답이 유효하지 않은 경우
     */
    public void acquire(long memberId, long problemId) {
        Long wait;
        try {
            String prefix = "submission:{" + memberId + "}:";
            wait
                = redis
                    .execute(SCRIPT, List.of(prefix + "minute", prefix + "problem:" + problemId, prefix + "sequence"));
        } catch (org.springframework.dao.DataAccessException exception) {
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        if (wait == null || wait < 0) {
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        if (wait > 0) {
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(wait));
            throw new JudgeException(JudgeErrorCode.SUBMISSION_RATE_LIMITED, headers, null);
        }
    }
}
