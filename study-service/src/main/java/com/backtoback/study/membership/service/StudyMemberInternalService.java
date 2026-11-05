package com.backtoback.study.membership.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.dto.StudyMembersResponse;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;

import lombok.RequiredArgsConstructor;

/**
 * 내부 API §5.7.1 — 스터디 구성원 조회 서비스.
 */
@Service
@RequiredArgsConstructor
public class StudyMemberInternalService {

    private final StudyRepository studyRepository;
    private final StudyMembershipRepository membershipRepository;

    @Transactional(readOnly = true)
    public StudyMembersResponse findMembers(Long studyId) {
        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));

        List<StudyMembership> memberships
            = membershipRepository.findAllByStudyIdAndStatus(studyId, MembershipStatus.APPROVED);

        List<StudyMembersResponse.StudyMember> members
            = memberships
                .stream()
                .map(m -> new StudyMembersResponse.StudyMember(m.getMemberId(), m.getId(), m.getRole().name()))
                .toList();

        return new StudyMembersResponse(study.getId(), study.getStatus().name(), members);
    }
}
