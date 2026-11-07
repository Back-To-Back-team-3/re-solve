package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.dto.request.ProblemValidationRequest;
import com.backtoback.problem.problem.dto.response.ProblemValidationResponse;

import jakarta.validation.Validator;

/** 코드 원문 보존과 저장 결과 재조회·파일 실패 분류의 예외 경계를 검증한다. */
@ExtendWith(MockitoExtension.class)
class ProblemValidationServiceFailureTest {
    @Mock
    private ProblemValidationStore store;
    @Mock
    private ProblemAssetUploadService uploads;
    @Mock
    private Validator validator;
    private ProblemValidationService service;
    private final ProblemValidationResponse response = new ProblemValidationResponse("1", "2", "java", "REQUESTED");

    /** 외부 의존 없이 입력 인코딩과 예외 복구 흐름을 구성한다. */
    @BeforeEach
    void setUp() {
        service = new ProblemValidationService(store, uploads, new ProblemImageStorageProperties("bucket"), validator);
        given(validator.validate(any(ProblemValidationRequest.class))).willReturn(Set.of());
    }

    /**
     * 잘못된 UTF-16을 저장·조회 전에 거절한다.
     *
     * @param unit 짝 없는 surrogate 코드 단위
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            0xD800,
            0xDC00
        }
    )
    @DisplayName("짝 없는 surrogate는 대체 문자로 저장하지 않고 400으로 거절한다")
    void rejectsMalformedCode(int unit) {
        assertThatThrownBy(() -> accept("code" + (char)unit))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
        verifyNoInteractions(store, uploads);
    }

    /** 보조 평면 문자를 포함한 원문의 UTF-8 바이트를 보존한다. */
    @Test
    @DisplayName("정상 surrogate 쌍과 한글 코드의 UTF-8 바이트는 그대로 업로드한다")
    void preservesValidUnicode() {
        given(uploads.uploadCode(anyString(), anyLong(), anyLong(), any())).willReturn(3L);
        given(store.save(any(), eq(3L))).willReturn(response);
        assertThat(accept("한글 🚀")).isEqualTo(response);
        var content = ArgumentCaptor.forClass(ProblemAssetContent.class);
        verify(uploads).uploadCode(eq("bucket"), eq(1L), eq(2L), content.capture());
        assertThat(content.getValue().bytes()).isEqualTo("한글 🚀".getBytes(StandardCharsets.UTF_8));
    }

    /** 재조회도 실패하면 원래 저장 예외와 파일 실패 분류를 보존한다. */
    @Test
    @DisplayName("저장과 재조회가 모두 실패하면 원래 저장 오류에 재조회 오류를 보존하고 파일을 분류한다")
    void preservesSaveFailure() {
        var failure = new IllegalStateException("save failed");
        var replayFailure = new IllegalArgumentException("replay failed");
        given(uploads.uploadCode(anyString(), anyLong(), anyLong(), any())).willReturn(3L);
        given(store.save(any(), eq(3L))).willThrow(failure);
        given(store.replay(eq(2L), eq("key"), anyString())).willReturn(Optional.empty()).willThrow(replayFailure);
        assertThatThrownBy(() -> accept("code")).isSameAs(failure);
        assertThat(failure.getSuppressed()).containsExactly(replayFailure);
        verify(store).recordLinkFailure(3L);
    }

    /** 확정 응답을 찾은 뒤 부가 실패 분류에 실패해도 응답을 보존한다. */
    @Test
    @DisplayName("저장 성공을 재조회했다면 파일 실패 분류 오류가 최초 응답을 덮지 않는다")
    void preservesReplayWhenTrackingFails() {
        var failure = new IllegalStateException("commit response lost");
        var trackingFailure = new IllegalArgumentException("tracking failed");
        given(uploads.uploadCode(anyString(), anyLong(), anyLong(), any())).willReturn(3L);
        given(store.save(any(), eq(3L))).willThrow(failure);
        given(store.replay(eq(2L), eq("key"), anyString()))
            .willReturn(Optional.empty())
            .willReturn(Optional.of(response));
        willThrow(trackingFailure).given(store).recordLinkFailure(3L);
        assertThat(accept("code")).isEqualTo(response);
        assertThat(failure.getSuppressed()).containsExactly(trackingFailure);
    }

    /** 멱등 충돌은 기존 오류 코드로 전달하고 파일 실패 분류를 유지한다. */
    @Test
    @DisplayName("재조회가 멱등 충돌이면 충돌 응답과 파일 실패 분류를 유지한다")
    void preservesReplayConflict() {
        var conflict = new ProblemException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        given(uploads.uploadCode(anyString(), anyLong(), anyLong(), any())).willReturn(3L);
        given(store.save(any(), eq(3L))).willThrow(new IllegalStateException("unique conflict"));
        given(store.replay(eq(2L), eq("key"), anyString())).willReturn(Optional.empty()).willThrow(conflict);
        assertThatThrownBy(() -> accept("code")).isSameAs(conflict);
        verify(store).recordLinkFailure(3L);
    }

    /**
     * 고정 식별자로 검증 코드를 접수한다.
     *
     * @param code 원래 코드
     * @return 접수 응답
     */
    private ProblemValidationResponse accept(String code) {
        return service.accept(1L, 2L, 2L, "key", new ProblemValidationRequest("java", code), "review-380", "127.0.0.1");
    }
}
