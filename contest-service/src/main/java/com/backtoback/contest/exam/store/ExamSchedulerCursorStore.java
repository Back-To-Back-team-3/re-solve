package com.backtoback.contest.exam.store;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** 순회 위치를 Redis에 공유하고 장애 중 미반영된 위치와 초기화를 복구 때 반영한다. */
@Component
@RequiredArgsConstructor
public class ExamSchedulerCursorStore {
    private static final Logger log = LoggerFactory.getLogger(ExamSchedulerCursorStore.class);
    private final StringRedisTemplate redis;
    private final Map<String, CursorState> localCursors = new ConcurrentHashMap<>();

    /** 미반영 상태는 공유 저장소보다 우선하며 동기화 실패 중에도 로컬 위치를 반환한다. */
    public Cursor load(String job) {
        var state = localCursors.compute(job, (name, local) -> {
            if (local != null && local.pending()) {
                return synchronize(name, local);
            }
            try {
                String value = redis.opsForValue().get(key(name));
                if (value == null) {
                    return local;
                }
                String[] parts = value.split("\\|", -1);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("Invalid cursor");
                }
                long id = Long.parseLong(parts[1]);
                if (id <= 0) {
                    throw new IllegalArgumentException("Invalid cursor");
                }
                return new CursorState(new Cursor(LocalDateTime.parse(parts[0]), id), false);
            } catch (RuntimeException failure) {
                log.warn("스케줄러 순회 위치 조회 실패: job={}, type={}", name, failure.getClass().getSimpleName());
                return local;
            }
        });
        return state == null ? null : state.cursor();
    }

    /** 처리 위치를 로컬에 보존하고 Redis 반영에 실패하면 다음 조회에서 재시도한다. */
    public void save(String job, Cursor cursor) {
        localCursors.compute(job, (name, local) -> synchronize(name, new CursorState(cursor, true)));
    }

    /** 미반영 초기화도 보존해 복구 후 이전 Redis 위치가 다시 나타나지 않게 한다. */
    public void clear(String job) {
        localCursors.compute(job, (name, local) -> synchronize(name, new CursorState(null, true)));
    }

    /** 같은 작업의 조회·저장·초기화는 맵의 작업별 연산 안에서 순서대로 반영한다. */
    private CursorState synchronize(String job, CursorState state) {
        try {
            if (state.cursor() == null) {
                redis.delete(key(job));
            } else {
                var cursor = state.cursor();
                redis.opsForValue().set(key(job), cursor.time() + "|" + cursor.id());
            }
            return new CursorState(state.cursor(), false);
        } catch (RuntimeException failure) {
            log.warn("스케줄러 순회 위치 동기화 실패: job={}, type={}", job, failure.getClass().getSimpleName());
            return state;
        }
    }

    private String key(String job) {
        return "contest:scheduler:cursor:" + job;
    }

    /** {@code null} 위치는 초기화이며 {@code pending}은 Redis 반영 재시도가 필요하다는 뜻이다. */
    private record CursorState(Cursor cursor, boolean pending) {
    }

    /** 시각과 식별자 순서의 마지막 처리 위치다. */
    public record Cursor(LocalDateTime time, long id) {
    }
}
