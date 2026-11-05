package com.backtoback.study.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.AssignmentCompletion;
import com.backtoback.study.assignment.domain.AssignmentProblem;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.backtoback.study.assignment.domain.CompletionStatus;
import com.backtoback.study.assignment.domain.CompletionType;
import com.backtoback.study.assignment.repository.AssignmentCompletionRepository;
import com.backtoback.study.assignment.repository.AssignmentMemberStatusRepository;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository;
import com.backtoback.study.assignment.repository.AssignmentRepository;
import com.backtoback.study.replica.domain.JudgeVerdict;
import com.backtoback.study.replica.domain.MemberAcReplica;
import com.backtoback.study.replica.repository.MemberAcReplicaRepository;

/**
 * 재채점 재계산 시나리오를 검증한다(정책 및 상태 §재채점 반영).
 */
@ExtendWith(MockitoExtension.class)
class AssignmentCompletionRecalculationServiceTest {

    private static final Long ASSIGNMENT_ID = 1L;
    private static final Long MEMBER_ID = 10L;
    private static final Long PROBLEM_ID = 100L;
    private static final Long SUBMISSION_ID = 200L;
    private static final Long OTHER_SUBMISSION_ID = 201L;

    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 11, 1, 0, 0);
    private static final LocalDateTime DEADLINE_AT = LocalDateTime.of(2026, 11, 7, 23, 59);
    private static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 11, 3, 10, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 5, 12, 0).truncatedTo(ChronoUnit.MICROS);
    private static final Instant NOW_INSTANT = NOW.toInstant(ZoneOffset.of("+09:00"));

    @Mock
    private AssignmentRepository assignmentRepository;
    @Mock
    private AssignmentProblemRepository assignmentProblemRepository;
    @Mock
    private AssignmentCompletionRepository completionRepository;
    @Mock
    private AssignmentMemberStatusRepository memberStatusRepository;
    @Mock
    private MemberAcReplicaRepository memberAcReplicaRepository;
    @Mock
    private CompletionTypeCalculator completionTypeCalculator;
    @Mock
    private Clock clock;

    @InjectMocks
    private AssignmentCompletionRecalculationService service;

    private Assignment assignment;
    private AssignmentProblem activeProblem;
    private MemberAcReplica basisAc;

    @BeforeEach
    void setUp() {
        given(clock.instant()).willReturn(NOW_INSTANT);

        assignment = Assignment.schedule(1L, "테스트 문제집", null, null, true, STARTS_AT, DEADLINE_AT);
        ReflectionTestUtils.setField(assignment, "id", ASSIGNMENT_ID);
        ReflectionTestUtils.setField(assignment, "status", AssignmentStatus.OPEN);

        activeProblem = AssignmentProblem.assign(ASSIGNMENT_ID, PROBLEM_ID, 1L, 1, "문제", 3, "[]", 1);
        basisAc = MemberAcReplica.sync(SUBMISSION_ID, MEMBER_ID, PROBLEM_ID, SUBMITTED_AT, 1, JudgeVerdict.AC, NOW);

        given(assignmentRepository.findById(ASSIGNMENT_ID)).willReturn(Optional.of(assignment));
    }

    @Test
    @DisplayName("유효 AC가 있고 완료 기록이 없으면 COMPLETED 행을 삽입한다")
    void recalculate_basisAcFound_noExisting_savesCompleted() {
        // given
        given(memberAcReplicaRepository.findEarliestBasisAc(eq(MEMBER_ID), eq(PROBLEM_ID), any(Pageable.class)))
            .willReturn(List.of(basisAc));
        given(completionRepository.findByAssignmentIdAndMemberIdAndProblemId(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID))
            .willReturn(Optional.empty());
        given(completionTypeCalculator.calculate(SUBMITTED_AT, STARTS_AT, DEADLINE_AT))
            .willReturn(CompletionType.ON_TIME);
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(completionRepository.findByAssignmentIdAndMemberId(ASSIGNMENT_ID, MEMBER_ID)).willReturn(List.of());

        // when
        service.recalculate(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID);

        // then
        ArgumentCaptor<AssignmentCompletion> captor = ArgumentCaptor.forClass(AssignmentCompletion.class);
        verify(completionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CompletionStatus.COMPLETED);
        assertThat(captor.getValue().getBasisSubmissionId()).isEqualTo(SUBMISSION_ID);
    }

    @Test
    @DisplayName("유효 AC가 없고 기존 COMPLETED 기록이 있으면 REVOKED로 전이한다")
    void recalculate_noBasisAc_existingCompleted_revokes() {
        // given
        given(memberAcReplicaRepository.findEarliestBasisAc(eq(MEMBER_ID), eq(PROBLEM_ID), any(Pageable.class)))
            .willReturn(List.of());
        AssignmentCompletion existing
            = AssignmentCompletion
                .complete(
                    ASSIGNMENT_ID,
                    MEMBER_ID,
                    PROBLEM_ID,
                    CompletionType.ON_TIME,
                    SUBMISSION_ID,
                    SUBMITTED_AT,
                    1,
                    NOW
                );
        given(completionRepository.findByAssignmentIdAndMemberIdAndProblemId(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID))
            .willReturn(Optional.of(existing));
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(completionRepository.findByAssignmentIdAndMemberId(ASSIGNMENT_ID, MEMBER_ID))
            .willReturn(List.of(existing));

        // when
        service.recalculate(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID);

        // then
        assertThat(existing.getStatus()).isEqualTo(CompletionStatus.REVOKED);
        verify(completionRepository, never()).save(any());
    }

    @Test
    @DisplayName("유효 AC가 있고 기존 REVOKED 기록이 있으면 COMPLETED로 복구한다")
    void recalculate_basisAcFound_existingRevoked_reactivates() {
        // given
        given(memberAcReplicaRepository.findEarliestBasisAc(eq(MEMBER_ID), eq(PROBLEM_ID), any(Pageable.class)))
            .willReturn(List.of(basisAc));
        AssignmentCompletion existing
            = AssignmentCompletion
                .complete(
                    ASSIGNMENT_ID,
                    MEMBER_ID,
                    PROBLEM_ID,
                    CompletionType.ON_TIME,
                    SUBMISSION_ID,
                    SUBMITTED_AT,
                    1,
                    NOW
                );
        existing.revoke();
        given(completionRepository.findByAssignmentIdAndMemberIdAndProblemId(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID))
            .willReturn(Optional.of(existing));
        given(completionTypeCalculator.calculate(SUBMITTED_AT, STARTS_AT, DEADLINE_AT))
            .willReturn(CompletionType.ON_TIME);
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(completionRepository.findByAssignmentIdAndMemberId(ASSIGNMENT_ID, MEMBER_ID))
            .willReturn(List.of(existing));

        // when
        service.recalculate(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID);

        // then
        assertThat(existing.getStatus()).isEqualTo(CompletionStatus.COMPLETED);
        assertThat(existing.getBasisSubmissionId()).isEqualTo(SUBMISSION_ID);
        verify(completionRepository, never()).save(any());
    }

    @Test
    @DisplayName("유효 AC가 있고 기존 근거가 동일하면 아무 변경도 하지 않는다")
    void recalculate_basisAcFound_sameBasis_noOp() {
        // given
        given(memberAcReplicaRepository.findEarliestBasisAc(eq(MEMBER_ID), eq(PROBLEM_ID), any(Pageable.class)))
            .willReturn(List.of(basisAc));
        AssignmentCompletion existing
            = AssignmentCompletion
                .complete(
                    ASSIGNMENT_ID,
                    MEMBER_ID,
                    PROBLEM_ID,
                    CompletionType.ON_TIME,
                    SUBMISSION_ID,
                    SUBMITTED_AT,
                    1,
                    NOW
                );
        given(completionRepository.findByAssignmentIdAndMemberIdAndProblemId(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID))
            .willReturn(Optional.of(existing));
        given(completionTypeCalculator.calculate(SUBMITTED_AT, STARTS_AT, DEADLINE_AT))
            .willReturn(CompletionType.ON_TIME);
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(completionRepository.findByAssignmentIdAndMemberId(ASSIGNMENT_ID, MEMBER_ID))
            .willReturn(List.of(existing));

        // when
        service.recalculate(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID);

        // then
        assertThat(existing.getCompletionType()).isEqualTo(CompletionType.ON_TIME);
        verify(completionRepository, never()).save(any());
    }

    @Test
    @DisplayName("유효 AC가 있고 기존 근거가 다르면 completion_type과 basis를 갱신한다")
    void recalculate_basisAcFound_differentBasis_updatesBasis() {
        // given
        MemberAcReplica newBasisAc
            = MemberAcReplica
                .sync(OTHER_SUBMISSION_ID, MEMBER_ID, PROBLEM_ID, DEADLINE_AT.plusHours(1), 2, JudgeVerdict.AC, NOW);
        given(memberAcReplicaRepository.findEarliestBasisAc(eq(MEMBER_ID), eq(PROBLEM_ID), any(Pageable.class)))
            .willReturn(List.of(newBasisAc));
        AssignmentCompletion existing
            = AssignmentCompletion
                .complete(
                    ASSIGNMENT_ID,
                    MEMBER_ID,
                    PROBLEM_ID,
                    CompletionType.ON_TIME,
                    SUBMISSION_ID,
                    SUBMITTED_AT,
                    1,
                    NOW
                );
        given(completionRepository.findByAssignmentIdAndMemberIdAndProblemId(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID))
            .willReturn(Optional.of(existing));
        given(completionTypeCalculator.calculate(DEADLINE_AT.plusHours(1), STARTS_AT, DEADLINE_AT))
            .willReturn(CompletionType.LATE);
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(completionRepository.findByAssignmentIdAndMemberId(ASSIGNMENT_ID, MEMBER_ID))
            .willReturn(List.of(existing));

        // when
        service.recalculate(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID);

        // then
        assertThat(existing.getCompletionType()).isEqualTo(CompletionType.LATE);
        assertThat(existing.getBasisSubmissionId()).isEqualTo(OTHER_SUBMISSION_ID);
        verify(completionRepository, never()).save(any());
    }

    @Test
    @DisplayName("재계산 후 모든 ACTIVE 문제가 완료되면 멤버 상태를 COMPLETED로 갱신한다")
    void recalculate_allCompleted_syncsMemberStatusCompleted() {
        // given
        given(memberAcReplicaRepository.findEarliestBasisAc(eq(MEMBER_ID), eq(PROBLEM_ID), any(Pageable.class)))
            .willReturn(List.of(basisAc));
        given(completionRepository.findByAssignmentIdAndMemberIdAndProblemId(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID))
            .willReturn(Optional.empty());
        given(completionTypeCalculator.calculate(SUBMITTED_AT, STARTS_AT, DEADLINE_AT))
            .willReturn(CompletionType.ON_TIME);
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        AssignmentCompletion savedCompletion
            = AssignmentCompletion
                .complete(
                    ASSIGNMENT_ID,
                    MEMBER_ID,
                    PROBLEM_ID,
                    CompletionType.ON_TIME,
                    SUBMISSION_ID,
                    SUBMITTED_AT,
                    1,
                    NOW
                );
        given(completionRepository.findByAssignmentIdAndMemberId(ASSIGNMENT_ID, MEMBER_ID))
            .willReturn(List.of(savedCompletion));

        // when
        service.recalculate(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID);

        // then
        verify(memberStatusRepository)
            .updateStatusAndCounts(eq(ASSIGNMENT_ID), eq(MEMBER_ID), eq("COMPLETED"), eq(1), anyInt(), any());
    }

    @Test
    @DisplayName("마감 후 완료 기록이 REVOKE되면 멤버 상태를 INCOMPLETE로 갱신한다")
    void recalculate_revokedAfterDeadline_syncsMemberStatusIncomplete() {
        // given
        Assignment pastDeadlineAssignment
            = Assignment.schedule(1L, "마감 문제집", null, null, true, STARTS_AT, STARTS_AT.plusDays(1));
        ReflectionTestUtils.setField(pastDeadlineAssignment, "id", ASSIGNMENT_ID);
        ReflectionTestUtils.setField(pastDeadlineAssignment, "status", AssignmentStatus.OPEN);
        given(assignmentRepository.findById(ASSIGNMENT_ID)).willReturn(Optional.of(pastDeadlineAssignment));

        given(memberAcReplicaRepository.findEarliestBasisAc(eq(MEMBER_ID), eq(PROBLEM_ID), any(Pageable.class)))
            .willReturn(List.of());
        AssignmentCompletion existing
            = AssignmentCompletion
                .complete(
                    ASSIGNMENT_ID,
                    MEMBER_ID,
                    PROBLEM_ID,
                    CompletionType.ON_TIME,
                    SUBMISSION_ID,
                    SUBMITTED_AT,
                    1,
                    NOW
                );
        given(completionRepository.findByAssignmentIdAndMemberIdAndProblemId(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID))
            .willReturn(Optional.of(existing));
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(completionRepository.findByAssignmentIdAndMemberId(ASSIGNMENT_ID, MEMBER_ID))
            .willReturn(List.of(existing));

        // when
        service.recalculate(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID);

        // then
        verify(memberStatusRepository)
            .updateStatusAndCounts(eq(ASSIGNMENT_ID), eq(MEMBER_ID), eq("INCOMPLETE"), eq(0), eq(0), eq(null));
    }

    @Test
    @DisplayName("마감 전 완료 기록이 REVOKE되면 멤버 상태를 IN_PROGRESS로 갱신한다")
    void recalculate_revokedBeforeDeadline_syncsMemberStatusInProgress() {
        // given
        given(memberAcReplicaRepository.findEarliestBasisAc(eq(MEMBER_ID), eq(PROBLEM_ID), any(Pageable.class)))
            .willReturn(List.of());
        AssignmentCompletion existing
            = AssignmentCompletion
                .complete(
                    ASSIGNMENT_ID,
                    MEMBER_ID,
                    PROBLEM_ID,
                    CompletionType.ON_TIME,
                    SUBMISSION_ID,
                    SUBMITTED_AT,
                    1,
                    NOW
                );
        given(completionRepository.findByAssignmentIdAndMemberIdAndProblemId(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID))
            .willReturn(Optional.of(existing));
        given(assignmentProblemRepository.findByAssignmentIdAndStatus(ASSIGNMENT_ID, AssignmentProblemStatus.ACTIVE))
            .willReturn(List.of(activeProblem));
        given(completionRepository.findByAssignmentIdAndMemberId(ASSIGNMENT_ID, MEMBER_ID))
            .willReturn(List.of(existing));

        // when
        service.recalculate(ASSIGNMENT_ID, MEMBER_ID, PROBLEM_ID);

        // then — REVOKED 기록이 남으므로 NOT_STARTED가 아니라 IN_PROGRESS
        verify(memberStatusRepository)
            .updateStatusAndCounts(eq(ASSIGNMENT_ID), eq(MEMBER_ID), eq("IN_PROGRESS"), eq(0), eq(0), eq(null));
    }
}
