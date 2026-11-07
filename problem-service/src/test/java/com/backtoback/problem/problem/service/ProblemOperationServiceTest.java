package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.repository.ProblemStateOperationRepository;

/** 내부 작업 조회의 입력 경계를 검증한다. */
@ExtendWith(MockitoExtension.class)
class ProblemOperationServiceTest {
    @Mock
    private ProblemStateOperationRepository operations;
    @InjectMocks
    private ProblemOperationService service;

    /**
     * 양수가 아닌 문제 ID는 저장소 조회 전에 거절한다.
     *
     * @param problemId 잘못된 문제 ID
     */
    @ParameterizedTest
    @ValueSource(
        longs = {
            0,
            -1
        }
    )
    @DisplayName("양수가 아닌 문제 ID는 내부 작업 저장소를 조회하지 않는다")
    void invalidProblemId(long problemId) {
        assertThatThrownBy(() -> service.find(problemId, "operation"))
            .isInstanceOf(ProblemException.class)
            .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_INVALID_REQUEST);
        verifyNoInteractions(operations);
    }

    /**
     * 비어 있거나 최대 길이를 초과한 작업 ID를 저장소 조회 전에 거절한다.
     *
     * @param operationId 잘못된 작업 ID이며 길이 초과 표시는 101자로 확장한다
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
        strings = {
            " ",
            "\t",
            "too-long"
        }
    )
    @DisplayName("비어 있거나 100자를 초과한 작업 ID는 내부 작업 저장소를 조회하지 않는다")
    void invalidOperationId(String operationId) {
        String value = "too-long".equals(operationId) ? "a".repeat(101) : operationId;
        assertThatThrownBy(() -> service.find(1L, value))
            .isInstanceOf(ProblemException.class)
            .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_INVALID_REQUEST);
        verifyNoInteractions(operations);
    }
}
