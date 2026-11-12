package com.backtoback.problem.problem.dto.response;

import java.util.List;

import com.backtoback.problem.problem.service.ProblemHintContextService;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Study 수신 필드에 맞춘 현재 사용 회차 힌트 문맥. (API §2.6.6) */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProblemHintContextResponse(
    String problemId,
    String problemRevisionId,
    String problemTitle,
    String problemBody,
    List<String> problemTags,
    boolean isHintAvailable
) {
    /**
     * 비가용 입력은 명시적 {@code null}로 유지하고 식별자를 문자열로 변환한다.
     *
     * @param value 힌트 가능 여부와 입력 자료
     * @return 공통 래퍼 없는 내부 응답
     */
    public static ProblemHintContextResponse from(ProblemHintContextService.Context value) {
        return new ProblemHintContextResponse(
            Long.toString(value.problemId()),
            value.revisionId() == null ? null : value.revisionId().toString(),
            value.title(),
            value.body(),
            value.tags(),
            value.available()
        );
    }
}
