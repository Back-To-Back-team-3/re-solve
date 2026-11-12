package com.backtoback.study.membership.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.dto.MembershipListResponse;
import com.backtoback.study.membership.dto.StudyMembersResponse;
import com.backtoback.study.membership.service.StudyMemberInternalService;

import lombok.RequiredArgsConstructor;

/**
 * 내부 API §5.7.1·§5.7.2 — 스터디 구성원 조회 및 회원의 스터디 가입 목록 조회.
 */
@RestController
@RequestMapping("/internal/v1/studies")
@RequiredArgsConstructor
public class StudyMemberInternalController {

    private final StudyMemberInternalService studyMemberInternalService;

    @GetMapping("/{studyId}/members")
    public StudyMembersResponse getStudyMembers(@PathVariable Long studyId) {
        return studyMemberInternalService.findMembers(studyId);
    }

    /**
     * §5.7.2 회원의 스터디 가입 목록 조회 — member-service 탈퇴 차단 판단(LEADER 여부)에 사용한다.
     */
    @GetMapping("/memberships")
    public MembershipListResponse getMemberMemberships(
        @RequestParam Long memberId,
        @RequestParam(required = false) MembershipRole role,
        @RequestParam(defaultValue = "true") boolean activeOnly
    ) {
        return studyMemberInternalService.findMemberMemberships(memberId, role, activeOnly);
    }
}
