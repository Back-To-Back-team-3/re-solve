package com.backtoback.problem.problem.dto.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** 일반 공개 문제 본문과 본인 풀이 상태. */
public record ProblemDetailResponse(@JsonUnwrapped ProblemContentResponse content, String solvedStatus) {
    /**
     * 일반 열람 본문에 회원별 풀이 상태를 추가한다.
     *
     * @param content 고정 본문
     * @param solved 해결 여부
     * @return 일반 상세
     */
    public static ProblemDetailResponse from(ProblemContentResponse content, boolean solved) {
        return new ProblemDetailResponse(content, solved ? "SOLVED" : "UNSOLVED");
    }
}
