package com.backtoback.contest.exam.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Redis 장애 중에도 같은 인스턴스의 마지막 처리 위치를 유지한다. */
@ExtendWith(MockitoExtension.class)
class ExamSchedulerCursorStoreTest {
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;

    @Test
    @DisplayName("Redis 조회와 저장 실패 뒤에도 진행 위치를 읽고 순회 끝에서 초기화한다")
    void retainsCursorDuringRedisFailure() {
        // given
        given(redis.opsForValue()).willReturn(values);
        given(values.get(anyString())).willThrow(new IllegalStateException("offline"));
        org.mockito.Mockito.doThrow(new IllegalStateException("offline")).when(values).set(anyString(), anyString());
        var store = new ExamSchedulerCursorStore(redis);
        var cursor = new ExamSchedulerCursorStore.Cursor(LocalDateTime.parse("2026-11-08T00:00:00"), 101);

        // when
        store.save("reconciliation", cursor);
        // then
        assertThat(store.load("reconciliation")).isEqualTo(cursor);
        store.clear("reconciliation");
        assertThat(store.load("reconciliation")).isNull();
    }

    @Test
    @DisplayName("저장 실패 후 Redis가 복구되면 오래된 공유 위치 대신 미반영 위치를 먼저 동기화한다")
    void restoresUnsavedCursorBeforeReadingRedis() {
        // given
        var shared = new AtomicReference<>("2026-11-08T00:00|1");
        var offline = new AtomicBoolean(true);
        given(redis.opsForValue()).willReturn(values);
        given(values.get(anyString())).willAnswer(invocation -> shared.get());
        doAnswer(invocation -> {
            if (offline.get()) {
                throw new IllegalStateException("offline");
            }
            shared.set(invocation.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString());
        var store = new ExamSchedulerCursorStore(redis);
        var cursor = new ExamSchedulerCursorStore.Cursor(LocalDateTime.parse("2026-11-08T00:00:00"), 101);
        assertThat(store.load("reconciliation").id()).isEqualTo(1);
        // when
        store.save("reconciliation", cursor);
        assertThat(store.load("reconciliation")).isEqualTo(cursor);
        offline.set(false);
        // then
        assertThat(store.load("reconciliation")).isEqualTo(cursor);
        assertThat(shared.get()).isEqualTo("2026-11-08T00:00|101");
        assertThat(store.load("reconciliation")).isEqualTo(cursor);
        shared.set("2026-11-08T00:00|102");
        assertThat(store.load("reconciliation").id()).isEqualTo(102);
    }

    @Test
    @DisplayName("초기화 실패 후 Redis가 복구되면 이전 공유 위치를 되살리지 않고 삭제한다")
    void restoresFailedClearBeforeReadingRedis() {
        // given
        var shared = new AtomicReference<>("2026-11-08T00:00|101");
        var offline = new AtomicBoolean(true);
        given(redis.opsForValue()).willReturn(values);
        given(values.get(anyString())).willAnswer(invocation -> shared.get());
        given(redis.delete(anyString())).willAnswer(invocation -> {
            if (offline.get()) {
                throw new IllegalStateException("offline");
            }
            return shared.getAndSet(null) != null;
        });
        var store = new ExamSchedulerCursorStore(redis);
        assertThat(store.load("reconciliation").id()).isEqualTo(101);
        // when
        store.clear("reconciliation");
        assertThat(store.load("reconciliation")).isNull();
        offline.set(false);
        // then
        assertThat(store.load("reconciliation")).isNull();
        assertThat(shared.get()).isNull();
        assertThat(store.load("reconciliation")).isNull();
    }
}
