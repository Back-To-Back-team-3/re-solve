package com.backtoback.study.membership.dto;

import java.util.List;

/**
 * 내부 API §5.7.1 — 스터디 구성원 목록 응답.
 */
public record StudyMembersResponse(String studyId, String studyStatus, List<StudyMember> members) {

    public record StudyMember(String memberId, String membershipId, String role) {
    }
}
