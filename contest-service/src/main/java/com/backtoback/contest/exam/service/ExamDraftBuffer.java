package com.backtoback.contest.exam.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.exam.service.ExamDraftStore.SaveRequest;
import com.backtoback.contest.exam.service.ExamDraftStore.SaveResponse;
import com.backtoback.contest.global.error.ContestException;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

/** Redis의 참가자별 1초 버퍼에서 마지막 요청만 저장한다. */
@Service
@RequiredArgsConstructor
public class ExamDraftBuffer {
    private static final Logger log = LoggerFactory.getLogger(ExamDraftBuffer.class);
    private static final DefaultRedisScript<List> ENQUEUE = new DefaultRedisScript<>("""
        local time = redis.call('TIME')
        local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
        local due = tonumber(redis.call('GET', KEYS[1] .. ':due'))
        if not due or now >= due then
            due = now + 1000
            redis.call('SET', KEYS[1] .. ':due', string.format('%.0f', due), 'PX', 120000)
        end
        local key = KEYS[1] .. ':slot:' .. string.format('%.0f', due)
        redis.call('HSET', key, 'last', ARGV[1])
        redis.call('EXPIRE', key, 120)
        return {due, due - now}
        """, List.class);
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
        local time = redis.call('TIME')
        local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
        if redis.call('EXISTS', KEYS[1] .. ':lock') == 1 then return 50 end
        local next = tonumber(redis.call('GET', KEYS[1] .. ':next')) or 0
        if now < next then return next - now end
        redis.call('SET', KEYS[1] .. ':lock', ARGV[1], 'PX', 120000)
        return 0
        """, Long.class);
    private static final DefaultRedisScript<List> CLOSE_SLOT = new DefaultRedisScript<>("""
        local time = redis.call('TIME')
        local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
        local remaining = tonumber(ARGV[1]) - now
        if remaining > 0 then return {remaining, ''} end
        local last = redis.call('HGET', KEYS[1], 'last')
        if not last then return {-1, ''} end
        return {0, last}
        """, List.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1] .. ':lock') ~= ARGV[1] then return 0 end
        return redis.call('PEXPIRE', KEYS[1] .. ':lock', 120000)
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1] .. ':lock') ~= ARGV[1] then return 0 end
        local time = redis.call('TIME')
        local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
        redis.call('SET', KEYS[1] .. ':next', string.format('%.0f', now + 1000), 'PX', 120000)
        return redis.call('DEL', KEYS[1] .. ':lock')
        """, Long.class);
    private final StringRedisTemplate redis;
    private final ExamDraftStore store;
    private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "exam-draft-buffer-lock");
        thread.setDaemon(true);
        return thread;
    });

    /** 같은 1초 창의 마지막 요청을 커밋하고 나머지는 실제 DB 기준으로 응답한다. */
    public SaveResponse save(String examId, String memberId, String problemId, SaveRequest input) {
        var work = store.prepare(examId, memberId, problemId, input);
        String token = UUID.randomUUID().toString();
        String prefix = "exam:draft:buffer:" + work.participantId();
        List<?> slot;
        try {
            slot = redis.execute(ENQUEUE, List.of(prefix), token);
        } catch (RuntimeException failure) {
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
        if (slot == null || slot.size() != 2 || !(slot.get(0) instanceof Number due)
            || !(slot.get(1) instanceof Number delay)) {
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        String key = prefix + ":slot:" + due.longValue();
        waitMillis(delay.longValue());
        try {
            String last = lastAfterDeadline(key, due.longValue());
            if (last == null) {
                throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            if (token.equals(last)) {
                acquire(prefix, key, token);
                var renewal = renewals.scheduleAtFixedRate(() -> {
                    try {
                        redis.execute(RENEW, List.of(prefix), token);
                    } catch (RuntimeException failure) {
                        log.warn("Draft 버퍼 잠금 갱신 실패", failure);
                    }
                }, 30, 30, TimeUnit.SECONDS);
                try {
                    var response = store.save(work);
                    redis.opsForHash().put(key, "status", "done");
                    return response;
                } catch (RuntimeException failure) {
                    redis.opsForHash().put(key, "status", "failed");
                    throw failure;
                } finally {
                    renewal.cancel(false);
                    Long released = redis.execute(RELEASE, List.of(prefix), token);
                    if (!Long.valueOf(1).equals(released)) {
                        throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
                    }
                }
            }
            for (;;) {
                String status = (String)redis.opsForHash().get(key, "status");
                if ("done".equals(status) || "failed".equals(status)) {
                    return store.skipped(work);
                }
                if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                    throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
                }
                waitMillis(25);
            }
        } catch (ContestException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, failure);
        }
    }

    /** Redis 서버 시각으로 창 종료를 확인한 뒤 마지막 요청을 읽는다. */
    private String lastAfterDeadline(String key, long due) {
        for (;;) {
            List<?> result = redis.execute(CLOSE_SLOT, List.of(key), Long.toString(due));
            if (result == null || result.size() != 2 || !(result.get(0) instanceof Number remaining)) {
                throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            if (remaining.longValue() < 0) {
                throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            if (remaining.longValue() == 0) {
                return (String)result.get(1);
            }
            waitMillis(remaining.longValue());
        }
    }

    /** 이전 저장이 끝난 뒤 1초가 지날 때까지 참가자별 실행을 직렬화한다. */
    private void acquire(String prefix, String key, String token) {
        for (;;) {
            if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            Long delay = redis.execute(ACQUIRE, List.of(prefix), token);
            if (delay == null) {
                throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            }
            if (delay == 0) {
                return;
            }
            waitMillis(delay);
        }
    }

    /** 서버 종료 시 잠금 갱신 작업을 중단한다. */
    @PreDestroy
    public void close() {
        renewals.shutdown();
    }

    /** 대기 중 인터럽트는 보존하고 의존 장애로 처리한다. */
    private void waitMillis(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, interrupted);
        }
    }
}
