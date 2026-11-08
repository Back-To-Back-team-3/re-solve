package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.contest.exam.service.ExamDraftStore.SaveRequest;
import com.backtoback.contest.exam.service.ExamDraftStore.SaveResponse;
import com.backtoback.contest.exam.service.ExamDraftStore.Work;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 잠금 정리 장애가 저장 결과와 업무 예외를 바꾸지 않는지 검증한다. */
@ExtendWith(MockitoExtension.class)
class ExamDraftBufferTest {
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ExamDraftStore store;
    private ExamDraftBuffer buffer;

    @AfterEach
    void tearDown() {
        if (buffer != null) {
            buffer.close();
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "zero",
            "null",
            "exception"
        }
    )
    @DisplayName("잠금 해제 실패는 커밋된 Draft 저장 응답을 유지한다")
    void releaseFailurePreservesResponse(String releaseFailure) {
        // given
        var input = new SaveRequest("java", "코드", "1");
        var work = prepare(input, releaseFailure);
        var response = new SaveResponse(true, "1", null, null, null);
        given(store.save(work)).willReturn(response);
        // when
        var actual = buffer.save("55", "7", "101", input);
        // then
        assertThat(actual).isSameAs(response);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "zero",
            "null",
            "exception"
        }
    )
    @DisplayName("잠금 해제 실패는 원래 Draft 저장 예외를 유지한다")
    void releaseFailurePreservesOriginalException(String releaseFailure) {
        // given
        var input = new SaveRequest("java", "코드", "1");
        var work = prepare(input, releaseFailure);
        var failure = new ContestException(ContestErrorCode.EXAM_CLOSED);
        given(store.save(work)).willThrow(failure);
        // when & then
        assertThatThrownBy(() -> buffer.save("55", "7", "101", input)).isSameAs(failure);
    }

    private Work prepare(SaveRequest input, String releaseFailure) {
        buffer = new ExamDraftBuffer(redis, store);
        var work = new Work(55, 7, 9, 101, "JAVA", "코드", 1);
        given(store.prepare("55", "7", "101", input)).willReturn(work);
        given(redis.hasKey("exam:draft:buffer:9:slot:0")).willReturn(true);
        var token = new AtomicReference<String>();
        given(redis.execute(any(RedisScript.class), anyList(), any())).willAnswer(invocation -> {
            RedisScript<?> script = invocation.getArgument(0);
            if (script == ReflectionTestUtils.getField(ExamDraftBuffer.class, "ENQUEUE")) {
                token.set(invocation.getArgument(2));
                return List.of(0L, 0L);
            }
            if (script == ReflectionTestUtils.getField(ExamDraftBuffer.class, "CLOSE_SLOT")) {
                return List.of(0L, token.get());
            }
            if (script == ReflectionTestUtils.getField(ExamDraftBuffer.class, "ACQUIRE")) {
                return 0L;
            }
            if (script == ReflectionTestUtils.getField(ExamDraftBuffer.class, "RELEASE")) {
                return switch (releaseFailure) {
                    case "zero" -> 0L;
                    case "null" -> null;
                    default -> throw new IllegalStateException("Redis unavailable");
                };
            }
            return 1L;
        });
        return work;
    }
}
