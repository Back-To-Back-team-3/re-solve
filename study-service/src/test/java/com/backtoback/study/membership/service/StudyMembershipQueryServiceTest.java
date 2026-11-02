package com.backtoback.study.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.dto.MembershipListItem;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.replica.domain.MemberLearningProfileReplica;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.repository.MemberLearningProfileReplicaRepository;
import com.backtoback.study.replica.repository.MemberReplicaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 구성원·대기 신청 목록 조회 서비스 정상·예외 시나리오를 검증한다(API 명세서 §5.2.4).
 */
@ExtendWith(MockitoExtension.class)
class StudyMembershipQueryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 10, 0);
    private static final Long STUDY_ID = 1L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long MEMBER_ID_A = 20L;

    @Mock
    private StudyMembershipRepository membershipRepository;
    @Mock
    private MemberReplicaRepository memberReplicaRepository;
    @Mock
    private MemberLearningProfileReplicaRepository learningProfileReplicaRepository;

    @InjectMocks
    private StudyMembershipQueryService service;

    private StudyMembership requesterApproved;
    private StudyMembership requesterLeader;
    private StudyMembership memberApproved;
    private StudyMembership memberPending;
    private MemberReplica replicaA;

    @BeforeEach
    void setUp() {
        service
            = new StudyMembershipQueryService(
                membershipRepository,
                memberReplicaRepository,
                learningProfileReplicaRepository,
                new ObjectMapper()
            );

        requesterApproved
            = StudyMembership
                .apply(STUDY_ID, REQUESTER_ID, MembershipRole.MEMBER, MembershipStatus.APPROVED, NOW, null, NOW);
        ReflectionTestUtils.setField(requesterApproved, "id", 1L);

        requesterLeader
            = StudyMembership
                .apply(STUDY_ID, REQUESTER_ID, MembershipRole.LEADER, MembershipStatus.APPROVED, NOW, null, NOW);
        ReflectionTestUtils.setField(requesterLeader, "id", 2L);

        memberApproved
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID_A, MembershipRole.MEMBER, MembershipStatus.APPROVED, NOW, null, NOW);
        ReflectionTestUtils.setField(memberApproved, "id", 3L);

        memberPending
            = StudyMembership
                .apply(
                    STUDY_ID,
                    MEMBER_ID_A,
                    MembershipRole.MEMBER,
                    MembershipStatus.PENDING,
                    NOW,
                    NOW.plusDays(7),
                    null
                );
        ReflectionTestUtils.setField(memberPending, "id", 4L);

        replicaA = MemberReplica.sync(MEMBER_ID_A, "nick-a", null, MemberStatus.ACTIVE, 1L, NOW);
    }

    @Test
    @DisplayName("APPROVED 구성원이 APPROVED 목록을 조회하면 구성원 항목을 반환한다")
    void findMemberships_approved_returnsList() {
        // given
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(REQUESTER_ID), any()))
            .willReturn(Optional.of(requesterApproved));
        PageRequest pageable = PageRequest.of(0, 10, Sort.by("joinedAt").descending());
        given(membershipRepository.findByStudyIdAndStatus(STUDY_ID, MembershipStatus.APPROVED, pageable))
            .willReturn(new PageImpl<>(List.of(memberApproved)));
        given(memberReplicaRepository.findAllByMemberIdIn(any())).willReturn(List.of(replicaA));

        // when
        Page<MembershipListItem> result
            = service.findMemberships(STUDY_ID, REQUESTER_ID, MembershipStatus.APPROVED, pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        MembershipListItem item = result.getContent().get(0);
        assertThat(item.memberId()).isEqualTo(String.valueOf(MEMBER_ID_A));
        assertThat(item.nickname()).isEqualTo("nick-a");
        assertThat(item.status()).isEqualTo("APPROVED");
        assertThat(item.learningProfile()).isNull();
        assertThat(item.expiresAt()).isNull();
    }

    @Test
    @DisplayName("LEADER가 PENDING 목록을 조회하면 학습 프로필 포함 항목을 반환한다")
    void findMemberships_pending_leader_returnsListWithProfile() {
        // given
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(REQUESTER_ID), any()))
            .willReturn(Optional.of(requesterLeader));
        PageRequest pageable = PageRequest.of(0, 10, Sort.by("appliedAt").descending());
        given(membershipRepository.findByStudyIdAndStatus(STUDY_ID, MembershipStatus.PENDING, pageable))
            .willReturn(new PageImpl<>(List.of(memberPending)));
        given(memberReplicaRepository.findAllByMemberIdIn(any())).willReturn(List.of(replicaA));
        MemberLearningProfileReplica profile
            = MemberLearningProfileReplica.sync(MEMBER_ID_A, "취업", "{\"JAVA\":\"LV2\"}", "[\"java\"]", null, 1L, NOW);
        given(learningProfileReplicaRepository.findAllByMemberIdIn(any())).willReturn(List.of(profile));

        // when
        Page<MembershipListItem> result
            = service.findMemberships(STUDY_ID, REQUESTER_ID, MembershipStatus.PENDING, pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        MembershipListItem item = result.getContent().get(0);
        assertThat(item.status()).isEqualTo("PENDING");
        assertThat(item.learningProfile()).isNotNull();
        assertThat(item.learningProfile().goal()).isEqualTo("취업");
        assertThat(item.learningProfile().tagLevels()).containsEntry("JAVA", "LV2");
        assertThat(item.learningProfile().preferredLanguages()).containsExactly("java");
        assertThat(item.joinedAt()).isNull();
    }

    @Test
    @DisplayName("APPROVED 구성원이 PENDING 목록을 조회하려 하면 AUTH_ACCESS_DENIED 예외를 던진다")
    void findMemberships_pending_nonManager_throwsAccessDenied() {
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(REQUESTER_ID), any()))
            .willReturn(Optional.of(requesterApproved));

        assertThatThrownBy(
            () -> service
                .findMemberships(
                    STUDY_ID,
                    REQUESTER_ID,
                    MembershipStatus.PENDING,
                    PageRequest.of(0, 10, Sort.by("appliedAt").descending())
                )
        )
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
    }

    @Test
    @DisplayName("스터디 구성원이 아닌 사람이 조회하면 AUTH_ACCESS_DENIED 예외를 던진다")
    void findMemberships_notMember_throwsAccessDenied() {
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(REQUESTER_ID), any()))
            .willReturn(Optional.empty());

        assertThatThrownBy(
            () -> service
                .findMemberships(
                    STUDY_ID,
                    REQUESTER_ID,
                    MembershipStatus.APPROVED,
                    PageRequest.of(0, 10, Sort.by("joinedAt").descending())
                )
        )
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
    }

    @Test
    @DisplayName("목록이 비어 있으면 빈 페이지를 반환한다")
    void findMemberships_emptyResult_returnsEmptyPage() {
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(REQUESTER_ID), any()))
            .willReturn(Optional.of(requesterApproved));
        PageRequest pageable = PageRequest.of(0, 10, Sort.by("joinedAt").descending());
        given(membershipRepository.findByStudyIdAndStatus(STUDY_ID, MembershipStatus.APPROVED, pageable))
            .willReturn(Page.empty(pageable));

        Page<MembershipListItem> result
            = service.findMemberships(STUDY_ID, REQUESTER_ID, MembershipStatus.APPROVED, pageable);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("학습 프로필 JSON 파싱 실패 시 goal만 있는 스냅샷을 반환한다")
    void findMemberships_invalidJson_returnsFallbackSnapshot() {
        // given
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(eq(STUDY_ID), eq(REQUESTER_ID), any()))
            .willReturn(Optional.of(requesterLeader));
        PageRequest pageable = PageRequest.of(0, 10, Sort.by("appliedAt").descending());
        given(membershipRepository.findByStudyIdAndStatus(STUDY_ID, MembershipStatus.PENDING, pageable))
            .willReturn(new PageImpl<>(List.of(memberPending)));
        given(memberReplicaRepository.findAllByMemberIdIn(any())).willReturn(List.of(replicaA));
        MemberLearningProfileReplica brokenProfile
            = MemberLearningProfileReplica.sync(MEMBER_ID_A, "취업", "NOT_JSON", "ALSO_NOT", null, 1L, NOW);
        given(learningProfileReplicaRepository.findAllByMemberIdIn(any())).willReturn(List.of(brokenProfile));

        // when
        Page<MembershipListItem> result
            = service.findMemberships(STUDY_ID, REQUESTER_ID, MembershipStatus.PENDING, pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).learningProfile().goal()).isEqualTo("취업");
        assertThat(result.getContent().get(0).learningProfile().tagLevels()).isNull();
        assertThat(result.getContent().get(0).learningProfile().preferredLanguages()).isNull();
    }
}
