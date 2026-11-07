package com.backtoback.problem.admin.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.backtoback.problem.admin.service.AdminProblemQueryStore.Summary;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 원문을 포함하지 않는 회차 이력(API 명세서 §2.3.6). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminRevisionSummaryResponse(
    String problemRevisionId,
    int revisionNumber,
    String title,
    LocalDateTime publishedAt,
    String currentProblemRevisionId,
    List<String> supportedLanguages
) {
    /**
     * 고정 회차의 메타데이터를 변환한다.
     *
     * @param value 조회 결과
     * @return 이력 항목
     */
    public static AdminRevisionSummaryResponse from(Summary value) {
        var revision = value.revision();
        return new AdminRevisionSummaryResponse(
            revision.getId().toString(),
            revision.getRevisionNumber(),
            revision.getTitle(),
            revision.getCreatedAt(),
            value.current() == null ? null : value.current().toString(),
            value.languages()
        );
    }
}
