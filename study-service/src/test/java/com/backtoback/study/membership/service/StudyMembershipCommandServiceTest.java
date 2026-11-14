package com.backtoback.study.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.assignment.service.AssignmentEnrollmentService;
import com.backtoback.study.global.error.StudyErrorCode;
import com.backtoback.study.global.error.StudyException;
import com.backtoback.study.membership.domain.JoinType;
import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.Study;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.domain.StudyStatus;
import com.backtoback.study.membership.dto.StudyJoinResponse;
import com.backtoback.study.membership.repository.StudyMemberQuotaRepository;
import com.backtoback.study.membership.repository.StudyMembershipHistoryRepository;
import com.backtoback.study.membership.repository.StudyMembershipRepository;
import com.backtoback.study.membership.repository.StudyRepository;
import com.backtoback.study.replica.domain.MemberReplica;
import com.backtoback.study.replica.domain.MemberStatus;
import com.backtoback.study.replica.repository.MemberReplicaRepository;

/**
 * 스터디 가입 신청·즉시 가입 정상·예외 시나리오를 검증한다(API 명세서 §5.2.2).
 */
@ExtendWith(MockitoExtension.class)
class StudyMembershipCommandServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 1, 10, 0);
    private static final Instant NOW_INSTANT = NOW.toInstant(ZoneOffset.of("+09:00"));
    private static final Long STUDY_ID = 1L;
    private static final Long MEMBER_ID = 10L;
    private static final String CORR_ID = "corr-1";

    @Mock
    private MemberReplicaRepository memberReplicaRepository;
    @Mock
    private StudyRepository studyRepository;
    @Mock
    private StudyMembershipRepository membershipRepository;
    @Mock
    private StudyMembershipHistoryRepository historyRepository;
    @Mock
    private StudyMemberQuotaRepository quotaRepository;
    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private AssignmentEnrollmentService enrollmentService;
    @Mock
    private OutboxEventWriter outboxEventWriter;
    @Mock
    private Clock clock;

    @InjectMocks
    private StudyMembershipCommandService service;

    private Study approvalStudy;
    private Study instantStudy;

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(NOW_INSTANT);
        lenient().when(memberReplicaRepository.findByMemberId(MEMBER_ID)).thenReturn(Optional.empty());
        lenient()
            .when(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), any(), any()))
            .thenReturn(Optional.empty());

        approvalStudy = Study.recruit("스터디", "설명", "EMPLOYMENT", "LV2", "java", "EVENING", 5, JoinType.APPROVAL, false);
        ReflectionTestUtils.setField(approvalStudy, "id", STUDY_ID);
        ReflectionTestUtils.setField(approvalStudy, "status", StudyStatus.RECRUITING);

        instantStudy = Study.recruit("스터디", "설명", "EMPLOYMENT", "LV2", "java", "EVENING", 5, JoinType.INSTANT, false);
        ReflectionTestUtils.setField(instantStudy, "id", STUDY_ID);
        ReflectionTestUtils.setField(instantStudy, "status", StudyStatus.RECRUITING);
        ReflectionTestUtils.setField(instantStudy, "memberCount", 0);
        ReflectionTestUtils.setField(instantStudy, "capacity", 5);
    }

    @Test
    @DisplayName("APPROVAL 스터디에 가입 신청하면 PENDING 상태로 응답한다")
    void join_approval_returnsPending() {
        // given
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(approvalStudy));
        given(membershipRepository.save(any(StudyMembership.class))).willAnswer(inv -> {
            ReflectionTestUtils.setField((StudyMembership)inv.getArgument(0), "id", 20L);
            return inv.getArgument(0);
        });

        // when
        StudyJoinResponse response = service.join(STUDY_ID, MEMBER_ID, CORR_ID);

        // then
        assertThat(response.membershipId()).isEqualTo("20");
        assertThat(response.status()).isEqualTo(MembershipStatus.PENDING.name());
        assertThat(response.expiresAt()).isNotNull();
    }

    @Test
    @DisplayName("INSTANT 스터디에 즉시 가입하면 APPROVED 상태로 응답한다")
    void join_instant_returnsApproved() {
        // given
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(instantStudy));
        given(quotaRepository.incrementIfBelowLimit(MEMBER_ID)).willReturn(1);
        given(studyRepository.joinInstantIfRecruiting(STUDY_ID)).willReturn(1);
        given(membershipRepository.save(any(StudyMembership.class))).willAnswer(inv -> {
            ReflectionTestUtils.setField((StudyMembership)inv.getArgument(0), "id", 30L);
            return inv.getArgument(0);
        });

        // when
        StudyJoinResponse response = service.join(STUDY_ID, MEMBER_ID, CORR_ID);

        // then
        assertThat(response.membershipId()).isEqualTo("30");
        assertThat(response.status()).isEqualTo(MembershipStatus.APPROVED.name());
        assertThat(response.expiresAt()).isNull();
        verify(enrollmentService).enroll(eq(STUDY_ID), eq(MEMBER_ID), eq(30L));
        verify(outboxEventWriter)
            .append(eq("study_membership"), eq("StudyMemberJoined"), eq("v1"), eq("30"), eq(CORR_ID), any());
    }

    @Test
    @DisplayName("스터디를 찾을 수 없으면 STUDY_NOT_FOUND 예외를 던진다")
    void join_studyNotFound_throwsStudyNotFound() {
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.join(STUDY_ID, MEMBER_ID, CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_NOT_FOUND)
            );
    }

    @Test
    @DisplayName("모집 중이 아닌 스터디에 가입 신청하면 STUDY_NOT_RECRUITING 예외를 던진다")
    void join_notRecruiting_throwsStudyNotRecruiting() {
        ReflectionTestUtils.setField(approvalStudy, "status", StudyStatus.ACTIVE);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(approvalStudy));

        assertThatThrownBy(() -> service.join(STUDY_ID, MEMBER_ID, CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_NOT_RECRUITING)
            );
    }

    @Test
    @DisplayName("이미 가입 신청 중인 스터디에 재신청하면 STUDY_ALREADY_APPLIED 예외를 던진다")
    void join_duplicateApply_throwsAlreadyApplied() {
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(approvalStudy));
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), any(), any()))
            .willReturn(
                Optional
                    .of(
                        StudyMembership
                            .apply(STUDY_ID, MEMBER_ID, null, MembershipStatus.PENDING, NOW, NOW.plusDays(7), null)
                    )
            );

        assertThatThrownBy(() -> service.join(STUDY_ID, MEMBER_ID, CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_ALREADY_APPLIED)
            );
    }

    @Test
    @DisplayName("회원 참여 상한 초과이면 STUDY_JOIN_LIMIT_EXCEEDED 예외를 던진다")
    void join_instant_quotaExceeded_throwsLimitExceeded() {
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(instantStudy));
        given(quotaRepository.incrementIfBelowLimit(MEMBER_ID)).willReturn(0);

        assertThatThrownBy(() -> service.join(STUDY_ID, MEMBER_ID, CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_JOIN_LIMIT_EXCEEDED)
            );
    }

    @Test
    @DisplayName("정원 초과로 studies UPDATE가 0행이면 STUDY_FULL 예외를 던진다")
    void join_instant_studyFull_throwsStudyFull() {
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(instantStudy));
        given(quotaRepository.incrementIfBelowLimit(MEMBER_ID)).willReturn(1);
        given(studyRepository.joinInstantIfRecruiting(STUDY_ID)).willReturn(0);

        Study recruitingFull
            = Study.recruit("스터디", null, "EMPLOYMENT", "LV2", "java", "EVENING", 5, JoinType.INSTANT, false);
        ReflectionTestUtils.setField(recruitingFull, "id", STUDY_ID);
        ReflectionTestUtils.setField(recruitingFull, "status", StudyStatus.RECRUITING);
        ReflectionTestUtils.setField(recruitingFull, "memberCount", 5);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(recruitingFull));

        assertThatThrownBy(() -> service.join(STUDY_ID, MEMBER_ID, CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_FULL)
            );
    }

    @Test
    @DisplayName("정지된 회원이 가입하면 MEMBER_SUSPENDED 예외를 던진다")
    void join_suspended_throwsMemberSuspended() {
        MemberReplica replica = MemberReplica.sync(MEMBER_ID, "user1", null, MemberStatus.SUSPENDED, 1L, NOW);
        given(memberReplicaRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(replica));

        assertThatThrownBy(() -> service.join(STUDY_ID, MEMBER_ID, CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.MEMBER_SUSPENDED)
            );
    }

    // ─── cancelOrLeave ───────────────────────────────────────────────────────

    @Test
    @DisplayName("PENDING 멤버십을 취소하면 CANCELED로 전이된다")
    void cancelOrLeave_pending_canceled() {
        StudyMembership pending
            = StudyMembership
                .apply(
                    STUDY_ID,
                    MEMBER_ID,
                    MembershipRole.MEMBER,
                    MembershipStatus.PENDING,
                    NOW,
                    NOW.plusDays(7),
                    null
                );
        ReflectionTestUtils.setField(pending, "id", 40L);
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), any(), any()))
            .willReturn(Optional.of(pending));
        given(membershipRepository.cancelIfPending(40L)).willReturn(1);

        service.cancelOrLeave(STUDY_ID, MEMBER_ID, CORR_ID);

        verify(historyRepository).save(any());
    }

    @Test
    @DisplayName("APPROVED 멤버십을 탈퇴하면 LEFT로 전이되고 S2 이벤트를 발행한다")
    void cancelOrLeave_approved_left() {
        StudyMembership approved
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.MEMBER, MembershipStatus.APPROVED, NOW, null, NOW);
        ReflectionTestUtils.setField(approved, "id", 50L);
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), any(), any()))
            .willReturn(Optional.of(approved));
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(instantStudy));
        given(membershipRepository.leaveIfApproved(eq(50L), any())).willReturn(1);

        service.cancelOrLeave(STUDY_ID, MEMBER_ID, CORR_ID);

        verify(outboxEventWriter).append(any(), eq("StudyMemberLeft"), any(), eq("50"), eq(CORR_ID), any());
    }

    @Test
    @DisplayName("활성 멤버십이 없으면 STUDY_MEMBERSHIP_NOT_FOUND 예외를 던진다")
    void cancelOrLeave_notFound_throwsMembershipNotFound() {
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), any(), any()))
            .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelOrLeave(STUDY_ID, MEMBER_ID, CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_MEMBERSHIP_NOT_FOUND)
            );
    }

    @Test
    @DisplayName("스터디장이 탈퇴를 시도하면 STUDY_LEADER_DELEGATION_REQUIRED 예외를 던진다")
    void cancelOrLeave_leader_throwsLeaderDelegationRequired() {
        StudyMembership leader
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.LEADER, MembershipStatus.APPROVED, NOW, null, NOW);
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), any(), any()))
            .willReturn(Optional.of(leader));

        assertThatThrownBy(() -> service.cancelOrLeave(STUDY_ID, MEMBER_ID, CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_LEADER_DELEGATION_REQUIRED)
            );
    }

    @Test
    @DisplayName("MANAGER가 탈퇴하면 manager_count가 감소한다")
    void cancelOrLeave_manager_decrementsManagerCount() {
        StudyMembership manager
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.MANAGER, MembershipStatus.APPROVED, NOW, null, NOW);
        ReflectionTestUtils.setField(manager, "id", 50L);
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), any(), any()))
            .willReturn(Optional.of(manager));
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(instantStudy));
        given(membershipRepository.leaveIfApproved(eq(50L), any())).willReturn(1);

        service.cancelOrLeave(STUDY_ID, MEMBER_ID, CORR_ID);

        verify(studyRepository).decrementManagerCount(STUDY_ID);
    }

    @Test
    @DisplayName("MEMBER가 탈퇴하면 manager_count는 변경하지 않는다")
    void cancelOrLeave_member_doesNotDecrementManagerCount() {
        StudyMembership member
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.MEMBER, MembershipStatus.APPROVED, NOW, null, NOW);
        ReflectionTestUtils.setField(member, "id", 50L);
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), any(), any()))
            .willReturn(Optional.of(member));
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(instantStudy));
        given(membershipRepository.leaveIfApproved(eq(50L), any())).willReturn(1);

        service.cancelOrLeave(STUDY_ID, MEMBER_ID, CORR_ID);

        verify(studyRepository, never()).decrementManagerCount(any());
    }

    @Test
    @DisplayName("CLOSED 스터디에서 MANAGER가 탈퇴하면 manager_count는 감소하지만 leaveAndAutoReopen은 호출하지 않는다")
    void cancelOrLeave_closedStudy_manager_decrementsManagerCount_skipsReopen() {
        Study closedStudy
            = Study.recruit("스터디", "설명", "EMPLOYMENT", "LV2", "java", "EVENING", 5, JoinType.INSTANT, false);
        ReflectionTestUtils.setField(closedStudy, "id", STUDY_ID);
        ReflectionTestUtils.setField(closedStudy, "status", StudyStatus.CLOSED);
        StudyMembership manager
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.MANAGER, MembershipStatus.APPROVED, NOW, null, NOW);
        ReflectionTestUtils.setField(manager, "id", 50L);
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), any(), any()))
            .willReturn(Optional.of(manager));
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(closedStudy));
        given(membershipRepository.leaveIfApproved(eq(50L), any())).willReturn(1);

        service.cancelOrLeave(STUDY_ID, MEMBER_ID, CORR_ID);

        verify(studyRepository).decrementManagerCount(STUDY_ID);
        verify(studyRepository, never()).leaveAndAutoReopen(any());
    }

    // ─── approve ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LEADER가 PENDING 신청을 승인하면 4단계를 거쳐 문제집 편입을 수행한다")
    void approve_success_enrollsCalled() {
        Long membershipId = 60L;
        Long targetMemberId = 20L;
        StudyMembership leaderMembership
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.LEADER, MembershipStatus.APPROVED, NOW, null, NOW);
        StudyMembership pending
            = StudyMembership
                .apply(
                    STUDY_ID,
                    targetMemberId,
                    MembershipRole.MEMBER,
                    MembershipStatus.PENDING,
                    NOW,
                    NOW.plusDays(7),
                    null
                );
        ReflectionTestUtils.setField(pending, "id", membershipId);

        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), eq(MEMBER_ID), any()))
            .willReturn(Optional.of(leaderMembership));
        given(membershipRepository.findById(membershipId)).willReturn(Optional.of(pending));
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(approvalStudy));
        given(membershipRepository.approveIfPending(eq(membershipId), any())).willReturn(1);
        given(quotaRepository.incrementIfBelowLimit(targetMemberId)).willReturn(1);
        given(studyRepository.approveIfNotFull(STUDY_ID)).willReturn(1);

        service.approve(STUDY_ID, membershipId, MEMBER_ID, CORR_ID);

        verify(enrollmentService).enroll(eq(STUDY_ID), eq(targetMemberId), eq(membershipId));
        verify(historyRepository).save(any());
    }

    @Test
    @DisplayName("이미 처리된 신청을 승인하면 STUDY_MEMBERSHIP_STATUS_CONFLICT 예외를 던진다")
    void approve_alreadyProcessed_throwsStatusConflict() {
        Long membershipId = 61L;
        StudyMembership leaderMembership
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.LEADER, MembershipStatus.APPROVED, NOW, null, NOW);
        StudyMembership pending
            = StudyMembership
                .apply(STUDY_ID, 20L, MembershipRole.MEMBER, MembershipStatus.PENDING, NOW, NOW.plusDays(7), null);
        ReflectionTestUtils.setField(pending, "id", membershipId);
        StudyMembership alreadyApproved
            = StudyMembership.apply(STUDY_ID, 20L, MembershipRole.MEMBER, MembershipStatus.APPROVED, NOW, null, NOW);
        ReflectionTestUtils.setField(alreadyApproved, "id", membershipId);

        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), eq(MEMBER_ID), any()))
            .willReturn(Optional.of(leaderMembership));
        given(membershipRepository.findById(membershipId))
            .willReturn(Optional.of(pending))
            .willReturn(Optional.of(alreadyApproved));
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(approvalStudy));
        given(quotaRepository.incrementIfBelowLimit(20L)).willReturn(1);
        given(studyRepository.approveIfNotFull(STUDY_ID)).willReturn(1);
        given(membershipRepository.approveIfPending(eq(membershipId), any())).willReturn(0);

        assertThatThrownBy(() -> service.approve(STUDY_ID, membershipId, MEMBER_ID, CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_MEMBERSHIP_STATUS_CONFLICT)
            );
    }

    @Test
    @DisplayName("만료된 신청을 승인하면 STUDY_APPLICATION_EXPIRED 예외를 던진다")
    void approve_expired_throwsApplicationExpired() {
        Long membershipId = 62L;
        StudyMembership leaderMembership
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.LEADER, MembershipStatus.APPROVED, NOW, null, NOW);
        StudyMembership pending
            = StudyMembership
                .apply(STUDY_ID, 20L, MembershipRole.MEMBER, MembershipStatus.PENDING, NOW, NOW.plusDays(7), null);
        ReflectionTestUtils.setField(pending, "id", membershipId);
        StudyMembership expiredPending
            = StudyMembership
                .apply(STUDY_ID, 20L, MembershipRole.MEMBER, MembershipStatus.PENDING, NOW, NOW.minusDays(1), null);
        ReflectionTestUtils.setField(expiredPending, "id", membershipId);

        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), eq(MEMBER_ID), any()))
            .willReturn(Optional.of(leaderMembership));
        given(membershipRepository.findById(membershipId))
            .willReturn(Optional.of(pending))
            .willReturn(Optional.of(expiredPending));
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(approvalStudy));
        given(quotaRepository.incrementIfBelowLimit(20L)).willReturn(1);
        given(studyRepository.approveIfNotFull(STUDY_ID)).willReturn(1);
        given(membershipRepository.approveIfPending(eq(membershipId), any())).willReturn(0);

        assertThatThrownBy(() -> service.approve(STUDY_ID, membershipId, MEMBER_ID, CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_APPLICATION_EXPIRED)
            );
    }

    @Test
    @DisplayName("승인 시 정원 초과이면 STUDY_FULL 예외를 던진다")
    void approve_studyFull_throwsStudyFull() {
        Long membershipId = 63L;
        Long targetMemberId = 20L;
        StudyMembership leaderMembership
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.LEADER, MembershipStatus.APPROVED, NOW, null, NOW);
        StudyMembership pending
            = StudyMembership
                .apply(
                    STUDY_ID,
                    targetMemberId,
                    MembershipRole.MEMBER,
                    MembershipStatus.PENDING,
                    NOW,
                    NOW.plusDays(7),
                    null
                );
        ReflectionTestUtils.setField(pending, "id", membershipId);

        Study full = Study.recruit("스터디", null, "EMPLOYMENT", "LV2", "java", "EVENING", 5, JoinType.APPROVAL, false);
        ReflectionTestUtils.setField(full, "id", STUDY_ID);
        ReflectionTestUtils.setField(full, "status", StudyStatus.ACTIVE);
        ReflectionTestUtils.setField(full, "memberCount", 5);

        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), eq(MEMBER_ID), any()))
            .willReturn(Optional.of(leaderMembership));
        given(membershipRepository.findById(membershipId)).willReturn(Optional.of(pending));
        given(quotaRepository.incrementIfBelowLimit(targetMemberId)).willReturn(1);
        given(studyRepository.approveIfNotFull(STUDY_ID)).willReturn(0);
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(full));

        assertThatThrownBy(() -> service.approve(STUDY_ID, membershipId, MEMBER_ID, CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(StudyErrorCode.STUDY_FULL)
            );
    }

    // ─── reject ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LEADER가 PENDING 신청을 거절하면 REJECTED로 전이된다")
    void reject_success_historyRecorded() {
        Long membershipId = 70L;
        StudyMembership leaderMembership
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.LEADER, MembershipStatus.APPROVED, NOW, null, NOW);
        StudyMembership pending
            = StudyMembership
                .apply(STUDY_ID, 20L, MembershipRole.MEMBER, MembershipStatus.PENDING, NOW, NOW.plusDays(7), null);
        ReflectionTestUtils.setField(pending, "id", membershipId);

        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), eq(MEMBER_ID), any()))
            .willReturn(Optional.of(leaderMembership));
        given(membershipRepository.findById(membershipId)).willReturn(Optional.of(pending));
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(approvalStudy));
        given(membershipRepository.rejectIfPending(membershipId)).willReturn(1);

        service.reject(STUDY_ID, membershipId, MEMBER_ID, CORR_ID);

        verify(historyRepository).save(any());
    }

    @Test
    @DisplayName("MEMBER가 거절을 시도하면 AUTH_ACCESS_DENIED 예외를 던진다")
    void reject_member_throwsAccessDenied() {
        StudyMembership member
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.MEMBER, MembershipStatus.APPROVED, NOW, null, NOW);
        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), eq(MEMBER_ID), any()))
            .willReturn(Optional.of(member));

        assertThatThrownBy(() -> service.reject(STUDY_ID, 70L, MEMBER_ID, CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
    }

    // ─── remove ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LEADER가 MEMBER를 강제탈퇴하면 REMOVED로 전이된다")
    void remove_success_historyRecorded() {
        Long membershipId = 80L;
        Long targetMemberId = 30L;
        StudyMembership leaderMembership
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.LEADER, MembershipStatus.APPROVED, NOW, null, NOW);
        StudyMembership targetMember
            = StudyMembership
                .apply(STUDY_ID, targetMemberId, MembershipRole.MEMBER, MembershipStatus.APPROVED, NOW, null, NOW);
        ReflectionTestUtils.setField(targetMember, "id", membershipId);

        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), eq(MEMBER_ID), any()))
            .willReturn(Optional.of(leaderMembership));
        given(membershipRepository.findById(membershipId)).willReturn(Optional.of(targetMember));
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(instantStudy));
        given(membershipRepository.removeIfApproved(eq(membershipId), any(), any())).willReturn(1);

        service.remove(STUDY_ID, membershipId, MEMBER_ID, "규칙 위반", CORR_ID);

        verify(historyRepository).save(any());
    }

    @Test
    @DisplayName("MANAGER가 LEADER를 강제탈퇴하려 하면 AUTH_ACCESS_DENIED 예외를 던진다")
    void remove_managerTargetsLeader_throwsAccessDenied() {
        Long membershipId = 81L;
        Long targetMemberId = 30L;
        StudyMembership managerMembership
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.MANAGER, MembershipStatus.APPROVED, NOW, null, NOW);
        StudyMembership targetLeader
            = StudyMembership
                .apply(STUDY_ID, targetMemberId, MembershipRole.LEADER, MembershipStatus.APPROVED, NOW, null, NOW);
        ReflectionTestUtils.setField(targetLeader, "id", membershipId);

        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), eq(MEMBER_ID), any()))
            .willReturn(Optional.of(managerMembership));
        given(membershipRepository.findById(membershipId)).willReturn(Optional.of(targetLeader));

        assertThatThrownBy(() -> service.remove(STUDY_ID, membershipId, MEMBER_ID, "사유", CORR_ID))
            .isInstanceOfSatisfying(
                StudyException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
    }

    @Test
    @DisplayName("MANAGER를 강퇴하면 manager_count가 감소한다")
    void remove_manager_decrementsManagerCount() {
        Long membershipId = 82L;
        Long targetMemberId = 30L;
        StudyMembership leaderMembership
            = StudyMembership
                .apply(STUDY_ID, MEMBER_ID, MembershipRole.LEADER, MembershipStatus.APPROVED, NOW, null, NOW);
        StudyMembership targetManager
            = StudyMembership
                .apply(STUDY_ID, targetMemberId, MembershipRole.MANAGER, MembershipStatus.APPROVED, NOW, null, NOW);
        ReflectionTestUtils.setField(targetManager, "id", membershipId);

        given(membershipRepository.findByStudyIdAndMemberIdAndStatusIn(any(), eq(MEMBER_ID), any()))
            .willReturn(Optional.of(leaderMembership));
        given(membershipRepository.findById(membershipId)).willReturn(Optional.of(targetManager));
        given(studyRepository.findById(STUDY_ID)).willReturn(Optional.of(instantStudy));
        given(membershipRepository.removeIfApproved(eq(membershipId), any(), any())).willReturn(1);

        service.remove(STUDY_ID, membershipId, MEMBER_ID, "규칙 위반", CORR_ID);

        verify(studyRepository).decrementManagerCount(STUDY_ID);
    }
}
