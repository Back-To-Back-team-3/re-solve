package com.backtoback.problem.problem.dto.response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.backtoback.problem.problem.service.ProblemReadStore.Item;

/** 편입 용도별 가용성과 존재 조건에 따른 선택 요약. */
public record ProblemSummaryListResponse(List<Map<String, Object>> items) {
    /**
     * 존재 여부에 따라 필드를 생략하고 요청 순서를 유지한다.
     *
     * @param values 문제별 스냅샷
     * @return 내부 선택 요약
     */
    public static ProblemSummaryListResponse from(List<Item> values) {
        return new ProblemSummaryListResponse(values.stream().map(ProblemSummaryListResponse::item).toList());
    }

    /**
     * 없는 문제와 통과 회차 없는 문제를 구분한다.
     *
     * @param value 문제 스냅샷
     * @return 조건부 필드 객체
     */
    private static Map<String, Object> item(Item value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("problemId", Long.toString(value.id()));
        result.put("isAvailable", value.available());
        if (value.problem() == null) {
            return result;
        }
        result.put("scope", value.problem().getScope());
        result.put("visibility", value.problem().getVisibility());
        result.put("sourceVersion", value.problem().getRowVersion());
        var revision = value.revision();
        result.put("problemRevisionId", revision == null ? null : revision.getId().toString());
        result.put("revisionNumber", revision == null ? null : revision.getRevisionNumber());
        result.put("supportedLanguages", value.languages());
        if (revision != null) {
            result.put("title", revision.getTitle());
            result.put("difficulty", revision.getDifficulty());
            result
                .put(
                    "tags",
                    value
                        .tags()
                        .stream()
                        .map(tag -> new ProblemTagResponse(Long.toString(tag.id()), tag.name()))
                        .toList()
                );
        }
        return result;
    }
}
