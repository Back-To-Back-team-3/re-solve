package com.backtoback.contest.exam.store;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/** Redis 서버 시각으로 회원의 시험 제출 제한을 원자적으로 적용한다. */
@Component
@RequiredArgsConstructor
public class ExamSubmissionRateLimiter {
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

    /** 신규 직접 제출에만 분당 및 문제별 제한을 적용한다. */
    public void acquire(long memberId, long problemId) {
        String prefix = "contest:submission:{" + memberId + "}:";
        Long wait;
        try {
            wait
                = redis
                    .execute(SCRIPT, List.of(prefix + "minute", prefix + "problem:" + problemId, prefix + "sequence"));
        } catch (org.springframework.dao.DataAccessException failure) {
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
        if (wait == null || wait < 0) {
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        if (wait > 0) {
            var headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(wait));
            throw new ContestException(ContestErrorCode.SUBMISSION_RATE_LIMITED, headers);
        }
    }
}
