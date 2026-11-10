package com.backtoback.judge.submission.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.submission.service.AcceptedSubmissionSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 회원별 일반 풀이 AC 이력과 다음 페이지 커서. (API 명세서 §3.3.4) */
public record AcceptedSubmissionHistoryResponse(
    List<Item> items,
    @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor
) {
    /**
     * 한 행을 더 조회한 결과에서 다음 페이지의 존재 여부를 판정한다.
     *
     * @param rows 제출 ID 오름차순으로 조회한 정답 이력
     * @param size 최대 응답 행 수
     * @return 마지막 페이지의 다음 커서를 {@code null}로 반환하는 이력 페이지
     */
    public static AcceptedSubmissionHistoryResponse from(List<AcceptedSubmissionSummary> rows, int size) {
        var page = rows.stream().limit(size).map(Item::from).toList();
        String cursor = rows.size() > size ? page.getLast().submissionId() : null;
        return new AcceptedSubmissionHistoryResponse(page, cursor);
    }

    /** 복제본 재동기화에 필요한 정답 제출의 식별 정보. */
    public record Item(String submissionId, String problemId, OffsetDateTime submittedAt, int judgeAttempt) {
        /**
         * 원본 식별자와 회차를 보존하고 접수 시각에 서비스 시간대를 적용한다.
         *
         * @param row 정답 제출 요약
         * @return 내부 호출자가 복제본과 대조할 항목
         */
        private static Item from(AcceptedSubmissionSummary row) {
            return new Item(
                row.id().toString(),
                Long.toString(row.problemId()),
                row.submittedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
                row.judgeAttempt()
            );
        }
    }
}
