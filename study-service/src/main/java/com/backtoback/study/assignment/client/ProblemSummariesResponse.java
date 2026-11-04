package com.backtoback.study.assignment.client;

import java.util.List;

/**
 * problem 서비스 summaries 응답 (API 명세서 §2.6.5).
 */
public record ProblemSummariesResponse(List<ProblemSummaryItem> items) {

}
