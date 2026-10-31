package com.backtoback.problem.problem.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** 대회 전용 문제 연결 요청을 받는다. */
public record ContestProblemLinkRequest(
    @NotBlank(message = "대회 ID는 필수입니다.") String contestId,
    @NotBlank(message = "고정 회차 ID는 필수입니다.") String problemRevisionId,
    @Positive(message = "기준 버전은 1 이상이어야 합니다.") long rowVersion,
    @NotBlank(message = "관리자 회원 ID는 필수입니다.") String actorMemberId
) {
}
