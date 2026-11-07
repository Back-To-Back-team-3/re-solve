package com.backtoback.contest.exam.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;

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
        given(redis.opsForValue()).willReturn(values);
        given(values.get(anyString())).willThrow(new IllegalStateException("offline"));
        org.mockito.Mockito.doThrow(new IllegalStateException("offline")).when(values).set(anyString(), anyString());
        var store = new ExamSchedulerCursorStore(redis);
        var cursor = new ExamSchedulerCursorStore.Cursor(LocalDateTime.parse("2026-11-08T00:00:00"), 101);

        store.save("reconciliation", cursor);
        assertThat(store.load("reconciliation")).isEqualTo(cursor);
        store.clear("reconciliation");
        assertThat(store.load("reconciliation")).isNull();
    }
}
