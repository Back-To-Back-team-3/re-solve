package com.backtoback.study.assignment.dto;

import com.backtoback.study.assignment.domain.AssignmentMemberStatus;
import com.backtoback.study.assignment.domain.MemberProgressStatus;

/**
 * 요청자의 과제 진행 상태 (API 명세서 §5.4.2 myStatus).
 */
public record MyStatusResponse(MemberProgressStatus status, int completedCount, int onTimeCount) {

    public static MyStatusResponse from(AssignmentMemberStatus ams) {
        return new MyStatusResponse(ams.getStatus(), ams.getCompletedCount(), ams.getOnTimeCount());
    }
}
