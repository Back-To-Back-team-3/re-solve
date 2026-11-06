package com.backtoback.study.replica.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.study.assignment.domain.AssignmentMemberStatus;
import com.backtoback.study.assignment.domain.AssignmentProblemStatus;
import com.backtoback.study.assignment.domain.AssignmentStatus;
import com.backtoback.study.assignment.repository.AssignmentMemberStatusRepository;
import com.backtoback.study.assignment.repository.AssignmentProblemRepository;
import com.backtoback.study.assignment.service.AssignmentCompletionRecalculationService;
import com.backtoback.study.replica.domain.JudgeVerdict;
import com.backtoback.study.replica.domain.MemberAcReplica;
import com.backtoback.study.replica.event.dto.SubmissionJudgedPayload;
import com.backtoback.study.replica.repository.MemberAcReplicaRepository;

/**
 * SubmissionJudged 소비 케이스별 단위 테스트 (event-contract.md §J1, §1.5 순서 값 규칙).
 */
@ExtendWith(MockitoExtension.class)
class SubmissionJudgedConsumerServiceTest {

    private static final String SUBMISSION_ID = "200";
    private static final String USER_ID = "10";
    private static final String PROBLEM_ID = "100";
    private static final Long ASSIGNMENT_ID = 1L;

    @Spy
    private Clock clock = Clock.systemDefaultZone();
    @Mock
    private JdbcProcessedEventStore processedEventStore;
    @Mock
    private MemberAcReplicaRepository memberAcReplicaRepository;
    @Mock
    private AssignmentProblemRepository assignmentProblemRepository;
    @Mock
    private AssignmentMemberStatusRepository assignmentMemberStatusRepository;
    @Mock
    private AssignmentCompletionRecalculationService recalculationService;

    @InjectMocks
    private SubmissionJudgedConsumerService service;

    @Test
    @DisplayName("이미 처리된 eventId를 수신하면 재계산을 호출하지 않는다")
    void consume_duplicateEventId_skipsRecalculation() {
        // given
        SubmissionJudgedPayload payload = payload("AC", 1);
        willThrow(new DuplicateKeyException("중복"))
            .given(processedEventStore)
            .insert(any(EventMetadata.class), any(LocalDateTime.class));

        // when
        service.consume(payload);

        // then
        verify(memberAcReplicaRepository, never()).findBySubmissionId(any());
        verify(recalculationService, never()).recalculate(any(), any(), any());
    }

    @Test
    @DisplayName("신규 AC이면 복제본을 저장하고 완료 재계산을 호출한다")
    void consume_newAc_savesReplicaAndRecalculates() {
        // given
        SubmissionJudgedPayload payload = payload("AC", 1);
        given(memberAcReplicaRepository.findBySubmissionId(Long.parseLong(SUBMISSION_ID))).willReturn(Optional.empty());
        AssignmentMemberStatus ams = mockAms(ASSIGNMENT_ID);
        given(
            assignmentProblemRepository
                .findOpenAssignmentIdsByProblemId(
                    eq(Long.parseLong(PROBLEM_ID)),
                    eq(AssignmentProblemStatus.ACTIVE),
                    eq(AssignmentStatus.OPEN)
                )
        ).willReturn(List.of(ASSIGNMENT_ID));
        given(
            assignmentMemberStatusRepository
                .findByAssignmentIdInAndMemberId(eq(List.of(ASSIGNMENT_ID)), eq(Long.parseLong(USER_ID)))
        ).willReturn(List.of(ams));

        // when
        service.consume(payload);

        // then
        ArgumentCaptor<MemberAcReplica> captor = ArgumentCaptor.forClass(MemberAcReplica.class);
        verify(memberAcReplicaRepository).save(captor.capture());
        assertThat(captor.getValue().isAccepted()).isTrue();
        assertThat(captor.getValue().getVerdict()).isEqualTo(JudgeVerdict.AC);
        verify(recalculationService).recalculate(ASSIGNMENT_ID, Long.parseLong(USER_ID), Long.parseLong(PROBLEM_ID));
    }

    @Test
    @DisplayName("첫 비AC이면 복제본을 저장하지 않고 재계산도 호출하지 않는다")
    void consume_firstNonAc_skipsSaveAndRecalculation() {
        // given
        SubmissionJudgedPayload payload = payload("WA", 1);
        given(memberAcReplicaRepository.findBySubmissionId(Long.parseLong(SUBMISSION_ID))).willReturn(Optional.empty());

        // when
        service.consume(payload);

        // then
        verify(memberAcReplicaRepository, never()).save(any());
        verify(recalculationService, never()).recalculate(any(), any(), any());
    }

    @Test
    @DisplayName("역순 judgeAttempt이면 조건부 UPDATE가 0행을 반환하여 재계산을 호출하지 않는다")
    void consume_outOfOrderJudgeAttempt_skipsUpdateAndRecalculation() {
        // given
        SubmissionJudgedPayload payload = payload("WA", 1);
        given(memberAcReplicaRepository.findBySubmissionId(Long.parseLong(SUBMISSION_ID)))
            .willReturn(Optional.of(existingReplica(JudgeVerdict.AC, 2)));
        given(memberAcReplicaRepository.updateVerdictIfNewer(any(), anyInt(), any(), anyBoolean(), any()))
            .willReturn(0);

        // when
        service.consume(payload);

        // then
        verify(recalculationService, never()).recalculate(any(), any(), any());
    }

    @Test
    @DisplayName("순방향 재채점이면 조건부 UPDATE로 verdict를 갱신하고 완료 재계산을 호출한다")
    void consume_laterJudgeAttempt_updatesVerdictAndRecalculates() {
        // given
        SubmissionJudgedPayload payload = payload("WA", 2);
        given(memberAcReplicaRepository.findBySubmissionId(Long.parseLong(SUBMISSION_ID)))
            .willReturn(Optional.of(existingReplica(JudgeVerdict.AC, 1)));
        given(memberAcReplicaRepository.updateVerdictIfNewer(any(), anyInt(), any(), anyBoolean(), any()))
            .willReturn(1);
        AssignmentMemberStatus ams = mockAms(ASSIGNMENT_ID);
        given(
            assignmentProblemRepository
                .findOpenAssignmentIdsByProblemId(
                    eq(Long.parseLong(PROBLEM_ID)),
                    eq(AssignmentProblemStatus.ACTIVE),
                    eq(AssignmentStatus.OPEN)
                )
        ).willReturn(List.of(ASSIGNMENT_ID));
        given(
            assignmentMemberStatusRepository
                .findByAssignmentIdInAndMemberId(eq(List.of(ASSIGNMENT_ID)), eq(Long.parseLong(USER_ID)))
        ).willReturn(List.of(ams));

        // when
        service.consume(payload);

        // then
        verify(memberAcReplicaRepository)
            .updateVerdictIfNewer(eq(Long.parseLong(SUBMISSION_ID)), eq(2), eq(JudgeVerdict.WA), eq(false), any());
        verify(recalculationService).recalculate(ASSIGNMENT_ID, Long.parseLong(USER_ID), Long.parseLong(PROBLEM_ID));
    }

    @Test
    @DisplayName("대상 OPEN 문제집이 없으면 재계산을 호출하지 않는다")
    void consume_noOpenAssignment_skipsRecalculation() {
        // given
        SubmissionJudgedPayload payload = payload("AC", 1);
        given(memberAcReplicaRepository.findBySubmissionId(Long.parseLong(SUBMISSION_ID))).willReturn(Optional.empty());
        given(
            assignmentProblemRepository
                .findOpenAssignmentIdsByProblemId(
                    eq(Long.parseLong(PROBLEM_ID)),
                    eq(AssignmentProblemStatus.ACTIVE),
                    eq(AssignmentStatus.OPEN)
                )
        ).willReturn(List.of());

        // when
        service.consume(payload);

        // then
        verify(memberAcReplicaRepository).save(any(MemberAcReplica.class));
        verify(recalculationService, never()).recalculate(any(), any(), any());
    }

    private SubmissionJudgedPayload payload(String verdict, int judgeAttempt) {
        return new SubmissionJudgedPayload(
            UUID.randomUUID().toString(),
            "SubmissionJudged",
            "v1",
            "2026-11-05T12:00:00.000000+09:00",
            SUBMISSION_ID,
            "test-correlation-id",
            SUBMISSION_ID,
            USER_ID,
            PROBLEM_ID,
            verdict,
            judgeAttempt,
            "2026-11-05T10:00:00.000000+09:00",
            "2026-11-05T10:00:01.000000+09:00"
        );
    }

    private MemberAcReplica existingReplica(JudgeVerdict verdict, int judgeAttempt) {
        return MemberAcReplica
            .sync(
                Long.parseLong(SUBMISSION_ID),
                Long.parseLong(USER_ID),
                Long.parseLong(PROBLEM_ID),
                LocalDateTime.of(2026, 11, 5, 10, 0),
                judgeAttempt,
                verdict,
                LocalDateTime.of(2026, 11, 5, 10, 0, 1)
            );
    }

    private AssignmentMemberStatus mockAms(Long assignmentId) {
        AssignmentMemberStatus ams = mock(AssignmentMemberStatus.class);
        given(ams.getAssignmentId()).willReturn(assignmentId);
        return ams;
    }
}
