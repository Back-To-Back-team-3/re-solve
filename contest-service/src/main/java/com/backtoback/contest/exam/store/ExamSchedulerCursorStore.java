package com.backtoback.contest.exam.store;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** 재기동과 인스턴스 변경 뒤에도 스케줄러가 남은 페이지부터 순회하게 한다. */
@Component
@RequiredArgsConstructor
public class ExamSchedulerCursorStore {
    private static final Logger log = LoggerFactory.getLogger(ExamSchedulerCursorStore.class);
    private final StringRedisTemplate redis;
    private final Map<String, Cursor> localCursors = new ConcurrentHashMap<>();

    /** 저장소가 비어 있거나 사용할 수 없으면 처음부터 다시 순회한다. */
    public Cursor load(String job) {
        try {
            String value = redis.opsForValue().get(key(job));
            if (value == null) {
                return localCursors.get(job);
            }
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid cursor");
            }
            long id = Long.parseLong(parts[1]);
            if (id <= 0) {
                throw new IllegalArgumentException("Invalid cursor");
            }
            var cursor = new Cursor(LocalDateTime.parse(parts[0]), id);
            localCursors.put(job, cursor);
            return cursor;
        } catch (RuntimeException failure) {
            log.warn("스케줄러 순회 위치 조회 실패: job={}, type={}", job, failure.getClass().getSimpleName());
            return localCursors.get(job);
        }
    }

    /** 처리한 마지막 대상의 시각과 ID를 기록한다. */
    public void save(String job, Cursor cursor) {
        localCursors.put(job, cursor);
        try {
            redis.opsForValue().set(key(job), cursor.time() + "|" + cursor.id());
        } catch (RuntimeException failure) {
            log.warn("스케줄러 순회 위치 저장 실패: job={}, type={}", job, failure.getClass().getSimpleName());
        }
    }

    /** 한 바퀴를 마치면 다음 회차에 처음부터 다시 검사한다. */
    public void clear(String job) {
        localCursors.remove(job);
        try {
            redis.delete(key(job));
        } catch (RuntimeException failure) {
            log.warn("스케줄러 순회 위치 초기화 실패: job={}, type={}", job, failure.getClass().getSimpleName());
        }
    }

    private String key(String job) {
        return "contest:scheduler:cursor:" + job;
    }

    /** 시각과 식별자 순서의 마지막 처리 위치다. */
    public record Cursor(LocalDateTime time, long id) {
    }
}
