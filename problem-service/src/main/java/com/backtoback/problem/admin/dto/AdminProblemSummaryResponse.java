package com.backtoback.problem.admin.dto;

import java.util.List;

import com.backtoback.problem.admin.service.AdminProblemQueryStore.Summary;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 관리자 목록의 회차 메타데이터(API 명세서 §2.3.2). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminProblemSummaryResponse(
    String problemId,
    String problemRevisionId,
    int revisionNumber,
    String title,
    String authorId,
    String scope,
    String visibility,
    int rowVersion,
    String currentProblemRevisionId,
    List<String> supportedLanguages
) {
    /**
     * 목록 스냅샷을 외부 식별자 형식으로 변환한다.
     *
     * @param value 조회 스냅샷
     * @return 관리자 목록 항목
     */
    public static AdminProblemSummaryResponse from(Summary value) {
        var problem = value.problem();
        var revision = value.revision();
        return new AdminProblemSummaryResponse(
            problem.getId().toString(),
            revision.getId().toString(),
            revision.getRevisionNumber(),
            revision.getTitle(),
            problem.getAuthorId().toString(),
            problem.getScope().name(),
            problem.getVisibility().name(),
            problem.getRowVersion(),
            value.current() == null ? null : value.current().toString(),
            value.languages()
        );
    }
}
