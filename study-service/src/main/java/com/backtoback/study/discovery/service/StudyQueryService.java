package com.backtoback.study.discovery.service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.study.discovery.dto.StudyDetailResponse;
import com.backtoback.study.discovery.dto.StudySummaryResponse;
import com.backtoback.study.discovery.repository.StudySpecification;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import lombok.RequiredArgsConstructor;

/**
 * 스터디 탐색(목록·상세·내 스터디) 조회를 담당한다. 가입·운영처럼 상태를 바꾸는 처리는 다루지 않는다.
 */
@Service
@RequiredArgsConstructor
public class StudyQueryService {

    private static final List<MembershipStatus> IN_PROGRESS_STATUSES
        = List.of(MembershipStatus.PENDING, MembershipStatus.APPROVED);

    private final StudyRepository studyRepository;
    private final StudyMembershipRepository studyMembershipRepository;
    private final MemberReplicaRepository memberReplicaRepository;

    /**
     * 모집 중인 스터디를 조건으로 검색한다(API 명세서 5.1.1). 학습 프로필이 없어도 호출할 수 있다.
     */
    @Transactional(readOnly = true)
    public Page<StudySummaryResponse> searchStudies(
        String keyword,
        String goal,
        String skillLevel,
        String preferredLanguage,
        String activeTimeSlot,
        Pageable pageable
    ) {
        // Specification.and(null)은 이 버전의 Spring Data JPA에서 IllegalArgumentException을 던지므로
        // null인 선택 필터는 and로 엮기 전에 걸러낸다.
        Specification<Study> specification
            = Stream
                .of(
                    StudySpecification.statusEquals(StudyStatus.RECRUITING),
                    StudySpecification.keywordContains(keyword),
                    StudySpecification.goalEquals(goal),
                    StudySpecification.skillLevelEquals(skillLevel),
                    StudySpecification.preferredLanguageEquals(preferredLanguage),
                    StudySpecification.activeTimeSlotEquals(activeTimeSlot)
                )
                .filter(Objects::nonNull)
                .reduce(Specification::and)
                .orElseThrow();

        return studyRepository.findAll(specification, pageable).map(StudySummaryResponse::from);
    }

    /**
     * 스터디 상세를 조회한다(API 명세서 5.1.2). {@code CLOSED}를 포함한 모든 상태를 조회할 수 있다.
     * 존재하지 않으면 {@code STUDY_NOT_FOUND}(404)를 던진다.
     *
     * @param memberId 로그인하지 않았으면 {@code null}
     */
    @Transactional(readOnly = true)
    public StudyDetailResponse getStudyDetail(Long studyId, Long memberId) {
        Study study
            = studyRepository.findById(studyId).orElseThrow(() -> new StudyException(StudyErrorCode.STUDY_NOT_FOUND));

        StudyDetailResponse.Leader leader = findLeader(studyId);
        StudyDetailResponse.MyMembership myMembership = memberId == null ? null : findMyMembership(studyId, memberId);

        return StudyDetailResponse.of(study, leader, myMembership);
    }

    private StudyDetailResponse.Leader findLeader(Long studyId) {
        return studyMembershipRepository
            .findByStudyIdAndRoleAndStatus(studyId, MembershipRole.LEADER, MembershipStatus.APPROVED)
            .flatMap(leaderMembership -> memberReplicaRepository.findByMemberId(leaderMembership.getMemberId()))
            .map(StudyDetailResponse.Leader::from)
            .orElse(null);
    }

    private StudyDetailResponse.MyMembership findMyMembership(Long studyId, Long memberId) {
        return studyMembershipRepository
            .findByStudyIdAndMemberIdAndStatusIn(studyId, memberId, IN_PROGRESS_STATUSES)
            .map(StudyDetailResponse.MyMembership::from)
            .orElse(null);
    }
}
