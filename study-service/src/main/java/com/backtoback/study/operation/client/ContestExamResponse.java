package com.backtoback.study.operation.client;

import java.util.List;

/**
 * contest 서비스 시험 목록 조회 응답 (API 명세서 §4.7.5).
 *
 * @param items 시험 항목 목록
 * @param nextCursor 다음 페이지 커서. 마지막 페이지는 null
 * @param hasMore 다음 페이지 존재 여부
 */
public record ContestExamResponse(List<ExamItem> items, String nextCursor, boolean hasMore) {

    /**
     * @param examId 시험 ID
     * @param status 시험 상태
     */
    public record ExamItem(String examId, String status) {

    }
}
