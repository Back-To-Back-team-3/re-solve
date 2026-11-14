package com.backtoback.judge.judging.service.dto;

import java.time.LocalDateTime;

import com.backtoback.judge.judging.dto.ProblemValidationRequest;

/**
 * 선점한 검증 실행의 불변 소유권이다.
 *
 * @param request 원 요청
 * @param token 임대 토큰
 * @param attempt 실행 시도 번호
 * @param correlationId 최초 추적 ID
 * @param deadlineAt 실행 허용 기한
 */
public record ValidationLease(
    ProblemValidationRequest request,
    String token,
    int attempt,
    String correlationId,
    LocalDateTime deadlineAt
) {
}
