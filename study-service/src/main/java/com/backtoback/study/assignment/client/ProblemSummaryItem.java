package com.backtoback.study.assignment.client;

import java.util.List;

/**
 * problem 서비스 문제 요약 항목 (API 명세서 §2.6.5).
 */
public record ProblemSummaryItem(
    Long problemId,
    boolean isAvailable,
    Long problemRevisionId,
    int revisionNumber,
    String title,
    int difficulty,
    List<TagItem> tags,
    String scope,
    String visibility,
    String sourceVersion,
    List<String> supportedLanguages
) {

    /** 문제 태그 항목 */
    public record TagItem(Long tagId, String name) {

    }
}
