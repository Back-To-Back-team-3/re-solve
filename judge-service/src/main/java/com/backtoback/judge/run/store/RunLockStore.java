package com.backtoback.judge.run.store;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;

import lombok.RequiredArgsConstructor;

/** Redis 서버 시각과 소유 토큰으로 사용자별 Run 제한을 획득·갱신·회수한다. (스케줄러 §3.5) */
@Component
@RequiredArgsConstructor
public class RunLockStore {
    private static final DefaultRedisScript<String> ACQUIRE = new DefaultRedisScript<>("""
        if redis.call('EXISTS', KEYS[1]) == 1 then return '' end
        local time = redis.call('TIME')
        local now = time[1] * 1000 + math.floor(time[2] / 1000)
        local value = ARGV[1] .. '|' .. ARGV[2] .. '|' .. tostring(now)
        redis.call('ZADD', KEYS[2], now, ARGV[1])
        redis.call('PEXPIRE', KEYS[2], 30000)
        redis.call('SET', KEYS[1], value)
        return value
        """, String.class);
    private static final DefaultRedisScript<Long> HEARTBEAT = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        local time = redis.call('TIME')
        local now = time[1] * 1000 + math.floor(time[2] / 1000)
        if now - tonumber(ARGV[3]) >= 30000 then return 0 end
        local heartbeat = redis.call('ZSCORE', KEYS[2], ARGV[2])
        if not heartbeat or now - tonumber(heartbeat) >= 30000 then return 0 end
        redis.call('ZADD', KEYS[2], now, ARGV[2])
        redis.call('PEXPIRE', KEYS[2], 30000)
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        redis.call('DEL', KEYS[1])
        redis.call('ZREM', KEYS[2], ARGV[2])
        return 1
        """, Long.class);
    private static final DefaultRedisScript<Long> RECOVER = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
        local time = redis.call('TIME')
        local now = time[1] * 1000 + math.floor(time[2] / 1000)
        if now - tonumber(ARGV[3]) <= 40000 then return 0 end
        local heartbeat = redis.call('ZSCORE', KEYS[2], ARGV[2])
        if heartbeat and now - tonumber(heartbeat) < 30000 then return 0 end
        redis.call('DEL', KEYS[1])
        redis.call('ZREM', KEYS[2], ARGV[2])
        return 1
        """, Long.class);
    private final StringRedisTemplate redis;
    private final String instanceId = UUID.randomUUID().toString();

    /**
     * 일반·시험이 공유할 사용자별 실행 제한을 원자적으로 획득한다.
     *
     * @param memberId 인증하거나 내부 문맥에서 확인한 회원 ID
     * @return 소유 토큰과 Redis 서버 시작 시각
     * @throws JudgeException 실행 중이면 RUN_RATE_LIMITED, Redis 장애이면 COMMON_DEPENDENCY_UNAVAILABLE
     */
    public Lease acquire(long memberId) {
        if (memberId <= 0) {
            throw new JudgeException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        String token = UUID.randomUUID().toString();
        try {
            String value = redis.execute(ACQUIRE, List.of(key(memberId), registry(instanceId)), token, instanceId);
            if (value == null) {
                throw unavailable();
            }
            if (value.isEmpty()) {
                throw new JudgeException(JudgeErrorCode.RUN_RATE_LIMITED);
            }
            return new Lease(memberId, token, instanceId, value, Long.parseLong(value.split("\\|", -1)[2]));
        } catch (DataAccessException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    /**
     * 자신의 유효한 실행만 갱신하며 다른 토큰의 생존 시간은 바꾸지 않는다.
     *
     * @param lease 획득한 실행 소유권
     * @return 소유권과 30초 실행 기한이 유효하면 true
     */
    public boolean heartbeat(Lease lease) {
        return execute(HEARTBEAT, lease) == 1;
    }

    /**
     * 정상·예외 종료 시 자신의 제한만 해제한다. 중복 해제는 무시한다.
     *
     * @param lease 종료할 실행 소유권
     */
    public void release(Lease lease) {
        execute(RELEASE, lease);
    }

    /**
     * SCAN으로 중단 후보를 순회하고 서버 시각·토큰별 생존 여부를 삭제 시 다시 확인한다.
     * 50초 스케줄러 임대를 넘기지 않도록 한 회차의 순회 시간을 제한한다.
     *
     * @return 실제 회수한 실행 제한 수
     * @throws JudgeException Redis 장애나 불완전 응답이면 COMMON_DEPENDENCY_UNAVAILABLE
     */
    public int reconcile() {
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        int recovered = 0;
        try (var cursor = redis.scan(ScanOptions.scanOptions().match("run:*").count(200).build())) {
            while (cursor.hasNext() && System.nanoTime() < deadline) {
                String currentKey = cursor.next();
                if (!currentKey.matches("run:[1-9][0-9]*")) {
                    continue;
                }
                String value = redis.opsForValue().get(currentKey);
                if (value == null) {
                    continue;
                }
                String[] parts = value.split("\\|", -1);
                if (parts.length != 3) {
                    throw unavailable();
                }
                var lease
                    = new Lease(
                        Long.parseLong(currentKey.substring(4)),
                        parts[0],
                        parts[1],
                        value,
                        Long.parseLong(parts[2])
                    );
                recovered += recover(lease) ? 1 : 0;
            }
            return recovered;
        } catch (DataAccessException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    /** 관측 후 소유권이 바뀌었으면 중단 후보의 제한을 삭제하지 않는다. */
    boolean recover(Lease lease) {
        return execute(RECOVER, lease) == 1;
    }

    /** 소유 값·토큰·시작 시각을 같은 Lua 호출에서 전달한다. */
    private long execute(DefaultRedisScript<Long> script, Lease lease) {
        try {
            Long result
                = redis
                    .execute(
                        script,
                        List.of(key(lease.memberId()), registry(lease.instanceId())),
                        lease.value(),
                        lease.token(),
                        Long.toString(lease.startedAt())
                    );
            if (result == null || result < 0 || result > 1) {
                throw unavailable();
            }
            return result;
        } catch (DataAccessException failure) {
            throw unavailable();
        }
    }

    /** 회원의 일반·시험 실행이 공유하는 키를 만든다. */
    private String key(long memberId) {
        return "run:" + memberId;
    }

    /** 기동마다 분리되는 토큰별 하트비트 레지스트리 키를 만든다. */
    private String registry(String owner) {
        return "run:active:" + owner;
    }

    /** 외부 원문 없이 실행 제한 저장소의 장애를 반환한다. */
    private JudgeException unavailable() {
        return new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
    }

    /** 다른 요청의 제한을 삭제하지 않기 위한 불변 소유권과 서버 시작 시각(ms). */
    public record Lease(long memberId, String token, String instanceId, String value, long startedAt) {
    }
}
