package com.backtoback.study.membership.dto;

import com.backtoback.study.membership.repository.projection.MembershipWithStudyStatus;

/**
 * 가입 목록 조회 응답의 개별 항목 (API 명세서 §5.7.2).
 */
public record MembershipItemResponse(String studyId, String membershipId, String role, String studyStatus) {

    public static MembershipItemResponse from(MembershipWithStudyStatus projection) {
        return new MembershipItemResponse(
            String.valueOf(projection.getStudyId()),
            String.valueOf(projection.getId()),
            projection.getRole(),
            projection.getStudyStatus()
        );
    }
}
