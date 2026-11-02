package com.backtoback.study.membership.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.dto.LearningProfileSnapshot;
import com.backtoback.study.membership.dto.MembershipListItem;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.replica.domain.MemberLearningProfileReplica;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/**
 * 스터디 구성원·대기 신청 목록 조회 서비스(API 명세서 §5.2.4).
 */
@Service
@RequiredArgsConstructor
public class StudyMembershipQueryService {

    private final StudyMembershipRepository membershipRepository;
    private final MemberReplicaRepository memberReplicaRepository;
    private final MemberLearningProfileReplicaRepository learningProfileReplicaRepository;
    private final ObjectMapper objectMapper;

    /**
     * 구성원(APPROVED) 또는 대기 신청(PENDING) 목록을 반환한다(API 명세서 §5.2.4).
     * APPROVED는 해당 스터디 APPROVED 구성원만, PENDING은 LEADER·MANAGER만 조회할 수 있다.
     *
     * @throws StudyException STUDY_NOT_FOUND, AUTH_ACCESS_DENIED
     */
    @Transactional(readOnly = true)
    public Page<MembershipListItem> findMemberships(
        Long studyId,
        Long requestMemberId,
        MembershipStatus status,
        Pageable pageable
    ) {
        verifyAccess(studyId, requestMemberId, status);

        Page<StudyMembership> membershipPage = membershipRepository.findByStudyIdAndStatus(studyId, status, pageable);

        if (membershipPage.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        Set<Long> memberIds
            = membershipPage.getContent().stream().map(StudyMembership::getMemberId).collect(Collectors.toSet());

        Map<Long, MemberReplica> replicaByMemberId
            = memberReplicaRepository
                .findAllByMemberIdIn(memberIds)
                .stream()
                .collect(Collectors.toMap(MemberReplica::getMemberId, Function.identity()));

        if (status == MembershipStatus.APPROVED) {
            List<MembershipListItem> items
                = membershipPage
                    .getContent()
                    .stream()
                    .map(m -> MembershipListItem.ofApproved(m, replicaByMemberId.get(m.getMemberId())))
                    .collect(Collectors.toList());
            return new PageImpl<>(items, pageable, membershipPage.getTotalElements());
        }

        Map<Long, LearningProfileSnapshot> profileByMemberId
            = learningProfileReplicaRepository
                .findAllByMemberIdIn(memberIds)
                .stream()
                .collect(Collectors.toMap(MemberLearningProfileReplica::getMemberId, r -> toSnapshot(r)));

        List<MembershipListItem> items
            = membershipPage
                .getContent()
                .stream()
                .map(
                    m -> MembershipListItem
                        .ofPending(m, replicaByMemberId.get(m.getMemberId()), profileByMemberId.get(m.getMemberId()))
                )
                .collect(Collectors.toList());
        return new PageImpl<>(items, pageable, membershipPage.getTotalElements());
    }

    private void verifyAccess(Long studyId, Long requestMemberId, MembershipStatus status) {
        StudyMembership requester
            = membershipRepository
                .findByStudyIdAndMemberIdAndStatusIn(studyId, requestMemberId, List.of(MembershipStatus.APPROVED))
                .orElseThrow(() -> new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED));

        if (status == MembershipStatus.PENDING && requester.getRole() != MembershipRole.LEADER
            && requester.getRole() != MembershipRole.MANAGER) {
            throw new StudyException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
    }

    private LearningProfileSnapshot toSnapshot(MemberLearningProfileReplica replica) {
        try {
            JavaType tagLevelsType
                = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, String.class);
            Map<String, String> tagLevels
                = replica.getTagLevelsJson() != null ? objectMapper.readValue(replica.getTagLevelsJson(), tagLevelsType)
                    : null;
            JavaType languagesType = objectMapper.getTypeFactory().constructCollectionType(List.class, String.class);
            List<String> preferredLanguages
                = replica.getPreferredLanguagesJson() != null
                    ? objectMapper.readValue(replica.getPreferredLanguagesJson(), languagesType) : null;
            Object activeTimeSlot
                = replica.getActiveTimeSlotsJson() != null
                    ? objectMapper.readValue(replica.getActiveTimeSlotsJson(), Object.class) : null;
            return new LearningProfileSnapshot(replica.getGoal(), tagLevels, preferredLanguages, activeTimeSlot);
        } catch (tools.jackson.core.JacksonException e) {
            // JSON 파싱 실패 시 null 처리해 조회 자체를 막지 않는다(ERD §4.5.10 값 체계 미확정 주석).
            return new LearningProfileSnapshot(replica.getGoal(), null, null, null);
        }
    }
}
