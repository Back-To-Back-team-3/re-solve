package com.backtoback.study.membership.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.study.membership.dto.StudyMembersResponse;
import com.backtoback.study.membership.service.StudyMemberInternalService;

import lombok.RequiredArgsConstructor;

/**
 * 내부 API §5.7.1 — 스터디 구성원 조회 (contest-service 권한 확인용).
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
}
