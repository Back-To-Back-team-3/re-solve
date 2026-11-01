package com.backtoback.study.assignment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.AssignmentProblem;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.backtoback.study.assignment.repository.AssignmentCompletionRepository;
import com.backtoback.study.assignment.repository.AssignmentMemberStatusRepository;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.replica.domain.JudgeVerdict;
import com.backtoback.study.replica.domain.MemberAcReplica;
import com.backtoback.study.replica.repository.MemberAcReplicaRepository;

/**
 * 문제집 편입 시나리오를 검증한다(API 명세서 §5.2.2, §5.2.5).
 */
@ExtendWith(MockitoExtension.class)
class AssignmentEnrollmentServiceTest {

    private static final Long STUDY_ID = 1L;
    private static final Long MEMBER_ID = 10L;
    private static final Long MEMBERSHIP_ID = 100L;
    private static final Long ASSIGNMENT_ID = 200L;
    private static final Long PROBLEM_ID = 300L;
    private static final Long SUBMISSION_ID = 400L;

    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime DEADLINE_AT = LocalDateTime.of(2026, 11, 7, 23, 59);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0);
    private static final Instant NOW_INSTANT = NOW.toInstant(ZoneOffset.of("+09:00"));

    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private AssignmentProblemRepository assignmentProblemRepository;
    @Mock
    private AssignmentMemberStatusRepository memberStatusRepository;
    @Mock
    private AssignmentCompletionRepository completionRepository;
    @Mock
    private MemberAcReplicaRepository acReplicaRepository;
    @Mock
    private Clock clock;

    @InjectMocks
    private AssignmentEnrollmentService service;

    private Assignment openAssignment;
    private AssignmentProblem activeProblem;

    @BeforeEach
    void setUp() {
        given(clock.instant()).willReturn(NOW_INSTANT);

        openAssignment = Assignment.schedule(STUDY_ID, "진행 문제집", null, null, true, STARTS_AT, DEADLINE_AT);
        ReflectionTestUtils.setField(openAssignment, "id", ASSIGNMENT_ID);
        ReflectionTestUtils.setField(openAssignment, "status", AssignmentStatus.OPEN);

        activeProblem = AssignmentProblem.assign(ASSIGNMENT_ID, PROBLEM_ID, 1L, "문제 1", 3, "[]", 1);
    }

    @Test
    @DisplayName("SCHEDULED 문제집이면 NOT_STARTED 상태로 멤버 편입 행을 생성하고 완료 기록은 생성하지 않는다")
    void enroll_scheduledAssignment_insertsNotStarted() {
        // given
        Assignment scheduled = Assignment.schedule(STUDY_ID, "예정 문제집", null, null, false, STARTS_AT, DEADLINE_AT);
        ReflectionTestUtils.setField(scheduled, "id", ASSIGNMENT_ID);

        given(assignmentRepository.findByStudyIdAndStatusIn(eq(STUDY_ID), any())).willReturn(List.of(scheduled));

        // when
        service.enroll(STUDY_ID, MEMBER_ID, MEMBERSHIP_ID);

        // then
        verify(memberStatusRepository).insertIgnore(ASSIGNMENT_ID, MEMBER_ID, MEMBERSHIP_ID, "NOT_STARTED", 0, 0, null);
        verify(completionRepository, never())
            .insertIgnore(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("OPEN 문제집에 ACTIVE 문제가 없으면 NOT_STARTED로 편입하고 AC 조회는 수행하지 않는다")
    void enroll_openAssignment_noActiveProblems_insertsNotStarted() {
        // given
        given(assignmentRepository.findByStudyIdAndStatusIn(eq(STUDY_ID), any())).willReturn(List.of(openAssignment));
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of());

        // when
        service.enroll(STUDY_ID, MEMBER_ID, MEMBERSHIP_ID);

        // then
        verify(memberStatusRepository).insertIgnore(ASSIGNMENT_ID, MEMBER_ID, MEMBERSHIP_ID, "NOT_STARTED", 0, 0, null);
        verify(acReplicaRepository, never()).findAllAcceptedByMemberIdAndProblemIds(anyLong(), anyList());
    }

    @Test
    @DisplayName("OPEN 문제집에서 AC가 없으면 NOT_STARTED로 편입하고 완료 기록은 생성하지 않는다")
    void enroll_openAssignment_noAc_insertsNotStarted() {
        // given
        given(assignmentRepository.findByStudyIdAndStatusIn(eq(STUDY_ID), any())).willReturn(List.of(openAssignment));
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(acReplicaRepository.findAllAcceptedByMemberIdAndProblemIds(MEMBER_ID, List.of(PROBLEM_ID)))
            .willReturn(List.of());
        given(memberStatusRepository.insertIgnore(ASSIGNMENT_ID, MEMBER_ID, MEMBERSHIP_ID, "NOT_STARTED", 0, 0, null))
            .willReturn(1);

        // when
        service.enroll(STUDY_ID, MEMBER_ID, MEMBERSHIP_ID);

        // then
        verify(completionRepository, never())
            .insertIgnore(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("사전 풀이 허용이고 AC가 starts_at 이전이면 PRE_SOLVED 완료 기록을 생성한다")
    void enroll_openAssignment_preSolvedAllowed_acBeforeStartsAt_insertsPreSolved() {
        // given — openAssignment.preSolvedAllowed = true
        LocalDateTime beforeStart = STARTS_AT.minusDays(1);
        MemberAcReplica acBefore
            = MemberAcReplica.sync(SUBMISSION_ID, MEMBER_ID, PROBLEM_ID, beforeStart, 1, JudgeVerdict.AC, NOW);

        given(assignmentRepository.findByStudyIdAndStatusIn(eq(STUDY_ID), any())).willReturn(List.of(openAssignment));
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(acReplicaRepository.findAllAcceptedByMemberIdAndProblemIds(MEMBER_ID, List.of(PROBLEM_ID)))
            .willReturn(List.of(acBefore));
        given(
            memberStatusRepository
                .insertIgnore(
                    ASSIGNMENT_ID,
                    MEMBER_ID,
                    MEMBERSHIP_ID,
                    "COMPLETED",
                    1,
                    1,
                    NOW.truncatedTo(ChronoUnit.MICROS)
                )
        ).willReturn(1);

        // when
        service.enroll(STUDY_ID, MEMBER_ID, MEMBERSHIP_ID);

        // then
        verify(completionRepository)
            .insertIgnore(
                eq(ASSIGNMENT_ID),
                eq(MEMBER_ID),
                eq(PROBLEM_ID),
                eq("PRE_SOLVED"),
                eq(SUBMISSION_ID),
                eq(beforeStart),
                eq(1),
                eq(NOW.truncatedTo(ChronoUnit.MICROS))
            );
    }

    @Test
    @DisplayName("AC가 deadline_at 이내이면 ON_TIME 완료 기록을 생성한다")
    void enroll_openAssignment_acOnTime_insertsOnTime() {
        // given
        LocalDateTime onTime = STARTS_AT.plusDays(2);
        MemberAcReplica acOnTime
            = MemberAcReplica.sync(SUBMISSION_ID, MEMBER_ID, PROBLEM_ID, onTime, 1, JudgeVerdict.AC, NOW);

        given(assignmentRepository.findByStudyIdAndStatusIn(eq(STUDY_ID), any())).willReturn(List.of(openAssignment));
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(acReplicaRepository.findAllAcceptedByMemberIdAndProblemIds(MEMBER_ID, List.of(PROBLEM_ID)))
            .willReturn(List.of(acOnTime));
        given(
            memberStatusRepository
                .insertIgnore(
                    ASSIGNMENT_ID,
                    MEMBER_ID,
                    MEMBERSHIP_ID,
                    "COMPLETED",
                    1,
                    1,
                    NOW.truncatedTo(ChronoUnit.MICROS)
                )
        ).willReturn(1);

        // when
        service.enroll(STUDY_ID, MEMBER_ID, MEMBERSHIP_ID);

        // then
        verify(completionRepository)
            .insertIgnore(
                eq(ASSIGNMENT_ID),
                eq(MEMBER_ID),
                eq(PROBLEM_ID),
                eq("ON_TIME"),
                eq(SUBMISSION_ID),
                eq(onTime),
                eq(1),
                any()
            );
    }

    @Test
    @DisplayName("AC가 deadline_at 이후이면 LATE 완료 기록을 생성한다")
    void enroll_openAssignment_acLate_insertsLate() {
        // given
        LocalDateTime late = DEADLINE_AT.plusDays(1);
        MemberAcReplica acLate
            = MemberAcReplica.sync(SUBMISSION_ID, MEMBER_ID, PROBLEM_ID, late, 1, JudgeVerdict.AC, NOW);

        given(assignmentRepository.findByStudyIdAndStatusIn(eq(STUDY_ID), any())).willReturn(List.of(openAssignment));
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(acReplicaRepository.findAllAcceptedByMemberIdAndProblemIds(MEMBER_ID, List.of(PROBLEM_ID)))
            .willReturn(List.of(acLate));
        given(
            memberStatusRepository
                .insertIgnore(
                    ASSIGNMENT_ID,
                    MEMBER_ID,
                    MEMBERSHIP_ID,
                    "COMPLETED",
                    1,
                    0,
                    NOW.truncatedTo(ChronoUnit.MICROS)
                )
        ).willReturn(1);

        // when
        service.enroll(STUDY_ID, MEMBER_ID, MEMBERSHIP_ID);

        // then
        verify(completionRepository)
            .insertIgnore(
                eq(ASSIGNMENT_ID),
                eq(MEMBER_ID),
                eq(PROBLEM_ID),
                eq("LATE"),
                eq(SUBMISSION_ID),
                eq(late),
                eq(1),
                any()
            );
    }

    @Test
    @DisplayName("멤버 편입 행이 이미 존재하면(insertIgnore 0행) 완료 기록을 삽입하지 않는다")
    void enroll_openAssignment_alreadyEnrolled_skipsCompletions() {
        // given
        LocalDateTime onTime = STARTS_AT.plusDays(1);
        MemberAcReplica acOnTime
            = MemberAcReplica.sync(SUBMISSION_ID, MEMBER_ID, PROBLEM_ID, onTime, 1, JudgeVerdict.AC, NOW);

        given(assignmentRepository.findByStudyIdAndStatusIn(eq(STUDY_ID), any())).willReturn(List.of(openAssignment));
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(acReplicaRepository.findAllAcceptedByMemberIdAndProblemIds(MEMBER_ID, List.of(PROBLEM_ID)))
            .willReturn(List.of(acOnTime));
        given(
            memberStatusRepository.insertIgnore(anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyInt(), any())
        ).willReturn(0);

        // when
        service.enroll(STUDY_ID, MEMBER_ID, MEMBERSHIP_ID);

        // then
        verify(completionRepository, never())
            .insertIgnore(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("사전 풀이 불허이고 AC가 starts_at 이전이면 완료로 인정하지 않고 NOT_STARTED로 편입한다")
    void enroll_openAssignment_preSolvedNotAllowed_acBeforeStartsAt_notCounted() {
        // given
        Assignment notAllowed = Assignment.schedule(STUDY_ID, "사전 불허 문제집", null, null, false, STARTS_AT, DEADLINE_AT);
        ReflectionTestUtils.setField(notAllowed, "id", ASSIGNMENT_ID);
        ReflectionTestUtils.setField(notAllowed, "status", AssignmentStatus.OPEN);

        LocalDateTime beforeStart = STARTS_AT.minusDays(1);
        MemberAcReplica acBefore
            = MemberAcReplica.sync(SUBMISSION_ID, MEMBER_ID, PROBLEM_ID, beforeStart, 1, JudgeVerdict.AC, NOW);

        given(assignmentRepository.findByStudyIdAndStatusIn(eq(STUDY_ID), any())).willReturn(List.of(notAllowed));
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(acReplicaRepository.findAllAcceptedByMemberIdAndProblemIds(MEMBER_ID, List.of(PROBLEM_ID)))
            .willReturn(List.of(acBefore));
        given(memberStatusRepository.insertIgnore(ASSIGNMENT_ID, MEMBER_ID, MEMBERSHIP_ID, "NOT_STARTED", 0, 0, null))
            .willReturn(1);

        // when
        service.enroll(STUDY_ID, MEMBER_ID, MEMBERSHIP_ID);

        // then
        verify(memberStatusRepository).insertIgnore(ASSIGNMENT_ID, MEMBER_ID, MEMBERSHIP_ID, "NOT_STARTED", 0, 0, null);
        verify(completionRepository, never())
            .insertIgnore(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("사전 풀이 불허이고 starts_at 이전·이후 AC가 모두 있으면 starts_at 이후 가장 이른 AC로 ON_TIME 완료를 생성한다")
    void enroll_openAssignment_preSolvedNotAllowed_preAndPostStartAc_countsPostStart() {
        // given
        Assignment notAllowed = Assignment.schedule(STUDY_ID, "사전 불허 문제집", null, null, false, STARTS_AT, DEADLINE_AT);
        ReflectionTestUtils.setField(notAllowed, "id", ASSIGNMENT_ID);
        ReflectionTestUtils.setField(notAllowed, "status", AssignmentStatus.OPEN);

        Long onTimeSubmissionId = SUBMISSION_ID + 1;
        LocalDateTime beforeStart = STARTS_AT.minusDays(1);
        LocalDateTime onTime = STARTS_AT.plusDays(2);
        MemberAcReplica acBefore
            = MemberAcReplica.sync(SUBMISSION_ID, MEMBER_ID, PROBLEM_ID, beforeStart, 1, JudgeVerdict.AC, NOW);
        MemberAcReplica acOnTime
            = MemberAcReplica.sync(onTimeSubmissionId, MEMBER_ID, PROBLEM_ID, onTime, 2, JudgeVerdict.AC, NOW);

        given(assignmentRepository.findByStudyIdAndStatusIn(eq(STUDY_ID), any())).willReturn(List.of(notAllowed));
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(acReplicaRepository.findAllAcceptedByMemberIdAndProblemIds(MEMBER_ID, List.of(PROBLEM_ID)))
            .willReturn(List.of(acBefore, acOnTime));
        given(
            memberStatusRepository
                .insertIgnore(
                    ASSIGNMENT_ID,
                    MEMBER_ID,
                    MEMBERSHIP_ID,
                    "COMPLETED",
                    1,
                    1,
                    NOW.truncatedTo(ChronoUnit.MICROS)
                )
        ).willReturn(1);

        // when
        service.enroll(STUDY_ID, MEMBER_ID, MEMBERSHIP_ID);

        // then
        verify(completionRepository)
            .insertIgnore(
                eq(ASSIGNMENT_ID),
                eq(MEMBER_ID),
                eq(PROBLEM_ID),
                eq("ON_TIME"),
                eq(onTimeSubmissionId),
                eq(onTime),
                eq(2),
                eq(NOW.truncatedTo(ChronoUnit.MICROS))
            );
    }
}
