package com.backtoback.study.assignment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.MemberProgressStatus;
import com.backtoback.study.assignment.repository.AssignmentMemberStatusRepository;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyMembership;
import com.backtoback.study.membership.repository.StudyMembershipRepository;

/**
 * AssignmentLifecycleService OPEN·CLOSE 전이 시나리오를 검증한다(스케줄러 명세서 §5.2).
 */
@ExtendWith(MockitoExtension.class)
class AssignmentLifecycleServiceTest {

    private static final Long STUDY_ID = 1L;
    private static final Long ASSIGNMENT_ID = 100L;
    private static final Long PREV_ASSIGNMENT_ID = 99L;
    private static final Long MEMBER_ID_1 = 10L;
    private static final Long MEMBER_ID_2 = 20L;
    private static final Long MEMBERSHIP_ID_1 = 1001L;
    private static final Long MEMBERSHIP_ID_2 = 1002L;
    private static final Long LEADER_MEMBER_ID = 30L;
    private static final String CORRELATION_ID = "sched-AssignmentLifecycleScheduler-test";

    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private AssignmentMemberStatusRepository memberStatusRepository;
    @Mock
    private StudyMembershipRepository membershipRepository;
    @Mock
    private AssignmentEnrollmentService enrollmentService;
    @Mock
    private OutboxEventWriter outboxEventWriter;

    private AssignmentLifecycleService lifecycleService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        lifecycleService
            = new AssignmentLifecycleService(
                assignmentRepository,
                memberStatusRepository,
                membershipRepository,
                enrollmentService,
                outboxEventWriter,
                clock
            );
    }

    @Test
    @DisplayName("openIfScheduled가 1이면 APPROVED 멤버를 편입하고 AssignmentPublished 이벤트를 기록한다")
    void openTransition_success() {
        Assignment assignment = mock(Assignment.class);
        StudyMembership m1 = mock(StudyMembership.class);
        StudyMembership m2 = mock(StudyMembership.class);

        given(assignment.getStudyId()).willReturn(STUDY_ID);
        given(assignment.getTitle()).willReturn("1주차 문제집");
        given(assignment.getStartsAt()).willReturn(LocalDateTime.of(2026, 11, 10, 9, 0, 0));
        given(m1.getMemberId()).willReturn(MEMBER_ID_1);
        given(m1.getId()).willReturn(MEMBERSHIP_ID_1);
        given(m2.getMemberId()).willReturn(MEMBER_ID_2);
        given(m2.getId()).willReturn(MEMBERSHIP_ID_2);

        given(assignmentRepository.openIfScheduled(ASSIGNMENT_ID)).willReturn(1);
        given(assignmentRepository.findById(ASSIGNMENT_ID)).willReturn(Optional.of(assignment));
        given(membershipRepository.findByStudyIdAndStatusWithLock(STUDY_ID, MembershipStatus.APPROVED))
            .willReturn(List.of(m1, m2));

        lifecycleService.openTransition(ASSIGNMENT_ID, CORRELATION_ID);

        verify(enrollmentService)
            .enrollOpenForScheduler(eq(assignment), eq(MEMBER_ID_1), eq(MEMBERSHIP_ID_1), any(LocalDateTime.class));
        verify(enrollmentService)
            .enrollOpenForScheduler(eq(assignment), eq(MEMBER_ID_2), eq(MEMBERSHIP_ID_2), any(LocalDateTime.class));
        verify(outboxEventWriter)
            .append(
                eq("assignment"),
                eq("AssignmentPublished"),
                eq("v1"),
                eq(String.valueOf(ASSIGNMENT_ID)),
                eq(CORRELATION_ID),
                any()
            );
    }

    @Test
    @DisplayName("openIfScheduled가 0이면 동시성 guard가 작동해 편입과 이벤트를 기록하지 않는다")
    void openTransition_skip_concurrency() {
        given(assignmentRepository.openIfScheduled(ASSIGNMENT_ID)).willReturn(0);

        lifecycleService.openTransition(ASSIGNMENT_ID, CORRELATION_ID);

        verify(assignmentRepository, never()).findById(any());
        verify(enrollmentService, never()).enrollOpenForScheduler(any(), any(), any(), any());
        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("closeIfOpen이 0이면 동시성 guard가 작동해 아무 작업도 하지 않는다")
    void closeTransition_skip_concurrency() {
        given(assignmentRepository.closeIfOpen(eq(ASSIGNMENT_ID), any(LocalDateTime.class))).willReturn(0);

        lifecycleService.closeTransition(ASSIGNMENT_ID, CORRELATION_ID);

        verify(memberStatusRepository, never()).updateToIncompleteExcludingLeft(any());
        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("closeIfOpen이 1이고 INCOMPLETE 멤버가 없으면 이벤트를 기록하지 않는다")
    void closeTransition_noIncompleteMembers_noEvent() {
        given(assignmentRepository.closeIfOpen(eq(ASSIGNMENT_ID), any(LocalDateTime.class))).willReturn(1);
        given(assignmentRepository.findById(ASSIGNMENT_ID)).willReturn(Optional.of(mock(Assignment.class)));
        given(
            memberStatusRepository.findMemberIdsByAssignmentIdAndStatus(ASSIGNMENT_ID, MemberProgressStatus.INCOMPLETE)
        ).willReturn(List.of());

        lifecycleService.closeTransition(ASSIGNMENT_ID, CORRELATION_ID);

        verify(memberStatusRepository).updateToIncompleteExcludingLeft(ASSIGNMENT_ID);
        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("INCOMPLETE 멤버가 있어도 직전 마감 문제집이 없으면 이벤트를 기록하지 않는다")
    void closeTransition_noPreviousAssignment_noEvent() {
        LocalDateTime deadline = LocalDateTime.of(2026, 11, 17, 23, 59, 59);
        Assignment assignment = mock(Assignment.class);
        given(assignment.getStudyId()).willReturn(STUDY_ID);
        given(assignment.getDeadlineAt()).willReturn(deadline);

        given(assignmentRepository.closeIfOpen(eq(ASSIGNMENT_ID), any(LocalDateTime.class))).willReturn(1);
        given(assignmentRepository.findById(ASSIGNMENT_ID)).willReturn(Optional.of(assignment));
        given(
            memberStatusRepository.findMemberIdsByAssignmentIdAndStatus(ASSIGNMENT_ID, MemberProgressStatus.INCOMPLETE)
        ).willReturn(List.of(MEMBER_ID_1));
        given(assignmentRepository.findPreviousClosedId(STUDY_ID, deadline)).willReturn(Optional.empty());

        lifecycleService.closeTransition(ASSIGNMENT_ID, CORRELATION_ID);

        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("직전 문제집에서 INCOMPLETE인 멤버가 없으면 이벤트를 기록하지 않는다")
    void closeTransition_noRepeatedMembers_noEvent() {
        LocalDateTime deadline = LocalDateTime.of(2026, 11, 17, 23, 59, 59);
        Assignment assignment = mock(Assignment.class);
        given(assignment.getStudyId()).willReturn(STUDY_ID);
        given(assignment.getDeadlineAt()).willReturn(deadline);

        given(assignmentRepository.closeIfOpen(eq(ASSIGNMENT_ID), any(LocalDateTime.class))).willReturn(1);
        given(assignmentRepository.findById(ASSIGNMENT_ID)).willReturn(Optional.of(assignment));
        given(
            memberStatusRepository.findMemberIdsByAssignmentIdAndStatus(ASSIGNMENT_ID, MemberProgressStatus.INCOMPLETE)
        ).willReturn(List.of(MEMBER_ID_1));
        given(assignmentRepository.findPreviousClosedId(STUDY_ID, deadline))
            .willReturn(Optional.of(PREV_ASSIGNMENT_ID));
        given(
            memberStatusRepository
                .findMemberIdsByAssignmentIdAndMemberIdsAndStatus(
                    eq(PREV_ASSIGNMENT_ID),
                    anyList(),
                    eq(MemberProgressStatus.INCOMPLETE)
                )
        ).willReturn(List.of());

        lifecycleService.closeTransition(ASSIGNMENT_ID, CORRELATION_ID);

        verify(outboxEventWriter, never()).append(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("연속 INCOMPLETE 멤버가 있으면 AssignmentIncompleteRepeated 이벤트를 기록한다")
    void closeTransition_repeatedIncomplete_eventPublished() {
        LocalDateTime deadline = LocalDateTime.of(2026, 11, 17, 23, 59, 59);
        Assignment assignment = mock(Assignment.class);
        given(assignment.getStudyId()).willReturn(STUDY_ID);
        given(assignment.getDeadlineAt()).willReturn(deadline);

        given(assignmentRepository.closeIfOpen(eq(ASSIGNMENT_ID), any(LocalDateTime.class))).willReturn(1);
        given(assignmentRepository.findById(ASSIGNMENT_ID)).willReturn(Optional.of(assignment));
        given(
            memberStatusRepository.findMemberIdsByAssignmentIdAndStatus(ASSIGNMENT_ID, MemberProgressStatus.INCOMPLETE)
        ).willReturn(List.of(MEMBER_ID_1, MEMBER_ID_2));
        given(assignmentRepository.findPreviousClosedId(STUDY_ID, deadline))
            .willReturn(Optional.of(PREV_ASSIGNMENT_ID));
        given(
            memberStatusRepository
                .findMemberIdsByAssignmentIdAndMemberIdsAndStatus(
                    eq(PREV_ASSIGNMENT_ID),
                    anyList(),
                    eq(MemberProgressStatus.INCOMPLETE)
                )
        ).willReturn(List.of(MEMBER_ID_1));
        given(
            membershipRepository
                .findMemberIdsByStudyIdAndRolesAndStatus(eq(STUDY_ID), anyList(), eq(MembershipStatus.APPROVED))
        ).willReturn(List.of(LEADER_MEMBER_ID));

        lifecycleService.closeTransition(ASSIGNMENT_ID, CORRELATION_ID);

        verify(outboxEventWriter)
            .append(
                eq("assignment"),
                eq("AssignmentIncompleteRepeated"),
                eq("v1"),
                eq(String.valueOf(ASSIGNMENT_ID)),
                eq(CORRELATION_ID),
                any()
            );
    }
}
