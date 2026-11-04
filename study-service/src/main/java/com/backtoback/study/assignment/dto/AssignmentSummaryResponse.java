package com.backtoback.study.assignment.dto;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.AssignmentMemberStatus;
import com.backtoback.study.assignment.domain.AssignmentStatus;

/**
 * 문제집 목록 항목 응답 (API 명세서 §5.4.2).
 */
public record AssignmentSummaryResponse(
    String id,
    String title,
    AssignmentStatus status,
    String startsAt,
    String deadlineAt,
    int activeProblemCount,
    MyStatusResponse myStatus
) {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");

    public static AssignmentSummaryResponse from(Assignment a, int activeCount, AssignmentMemberStatus ams) {
        return new AssignmentSummaryResponse(
            String.valueOf(a.getId()),
            a.getTitle(),
            a.getStatus(),
            toOffsetString(a.getStartsAt()),
            toOffsetString(a.getDeadlineAt()),
            activeCount,
            ams == null ? null : MyStatusResponse.from(ams)
        );
    }

    private static String toOffsetString(LocalDateTime ldt) {
        return ldt == null ? null : ldt.atOffset(ZoneOffset.of("+09:00")).format(FORMATTER);
    }
}
