package com.backtoback.contest.exam.store;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/** Redis 서버 시각으로 회원의 시험 제출 제한을 원자적으로 적용한다. */
@Component
@RequiredArgsConstructor
public class ExamSubmissionRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(ExamSubmissionRateLimiter.class);
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
        redis.call('ZADD', KEYS[1], now, ARGV[1])
        redis.call('PEXPIRE', KEYS[1], 60000)
        redis.call('SET', KEYS[2], ARGV[1], 'PX', 5000)
        return 0
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        redis.call('ZREM', KEYS[1], ARGV[1])
        if redis.call('GET', KEYS[2]) == ARGV[1] then
            redis.call('DEL', KEYS[2])
        end
        return 1
        """, Long.class);
    private final StringRedisTemplate redis;

    /** 신규 직접 제출에만 분당 및 문제별 제한을 적용한다. */
    public void acquire(long memberId, long problemId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("제출 제한은 접수 트랜잭션 안에서 예약해야 합니다.");
        }
        String prefix = "contest:submission:{" + memberId + "}:";
        var keys = List.of(prefix + "minute", prefix + "problem:" + problemId);
        String token = UUID.randomUUID().toString();
        Long wait;
        try {
            wait = redis.execute(SCRIPT, keys, token);
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
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        redis.execute(RELEASE, keys, token);
                    } catch (org.springframework.dao.DataAccessException failure) {
                        log.warn("제출 제한 예약 해제 실패: memberId={}, problemId={}", memberId, problemId, failure);
                    }
                }
            }
        });
    }
}
