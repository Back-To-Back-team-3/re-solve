package com.backtoback.problem.problem.dto.response;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import com.backtoback.problem.problem.service.ProblemReadStore.Item;

/** 일반 공개 문제의 사용 회차 검색 항목. */
public record ProblemSearchResponse(
    String problemId,
    String problemRevisionId,
    int revisionNumber,
    String title,
    int difficulty,
    List<ProblemTagResponse> tags,
    OffsetDateTime publishedAt,
    String solvedStatus,
    List<String> supportedLanguages
) {
    /**
     * 회원별 검색 결과를 공개 항목으로 변환한다.
     *
     * @param item 고정 사용 회차
     * @return 공개 검색 응답
     */
    public static ProblemSearchResponse from(Item item) {
        var revision = item.revision();
        return new ProblemSearchResponse(
            Long.toString(item.id()),
            revision.getId().toString(),
            revision.getRevisionNumber(),
            revision.getTitle(),
            revision.getDifficulty(),
            item.tags().stream().map(tag -> new ProblemTagResponse(Long.toString(tag.id()), tag.name())).toList(),
            revision.getCreatedAt().atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),
            item.solved() ? "SOLVED" : "UNSOLVED",
            item.languages()
        );
    }
}
