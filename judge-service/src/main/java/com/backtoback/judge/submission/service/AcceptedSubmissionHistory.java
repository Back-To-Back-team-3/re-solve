package com.backtoback.judge.submission.service;

import java.util.List;

/**
 * 제출 ID 순서의 일반 풀이 AC 이력 페이지.
 *
 * @param items 현재 페이지의 제출 요약
 * @param nextCursor 다음 페이지가 있을 때 현재 페이지의 마지막 제출 ID, 마지막 페이지이면 null
 */
public record AcceptedSubmissionHistory(List<AcceptedSubmissionSummary> items, Long nextCursor) {
}
