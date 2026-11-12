package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.contest.exam.client.ExamRunClient;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamMode;
import com.backtoback.contest.exam.domain.ExamParticipant;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamProblem;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.MemberReplicaRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 시험 Run의 입력·참가 상태와 고정 회차 전달을 확인한다. */
@ExtendWith(MockitoExtension.class)
class ExamRunServiceTest {
    @Mock
    private ExamRepository exams;
    @Mock
    private ExamProblemRepository problems;
    @Mock
    private ExamParticipantRepository participants;
    @Mock
    private MemberReplicaRepository members;
    @Mock
    private MemberReplicaService refresh;
    @Mock
    private ExamRunClient judge;
    @Mock
    private TransactionTemplate transactions;
    private ExamRunService service;

    /** 고정 서버 시각과 모의 의존성으로 서비스를 준비한다. */
    @BeforeEach
    void setUp() {
        service
            = new ExamRunService(
                exams,
                problems,
                participants,
                members,
                refresh,
                judge,
                transactions,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
            );
    }

    /** 요청 문제의 고정 회차를 Judge에 전달한다. */
    @Test
    @DisplayName("실제 입장한 활성 참가자의 Run은 고정 회차를 Judge에 전달한다")
    void sendsPinnedRevision() {
        // given
        given(refresh.refresh(7)).willReturn("ACTIVE");
        var exam
            = Exam
                .create(
                    12L,
                    7L,
                    "시험",
                    ExamMode.FIXED,
                    LocalDateTime.parse("2026-01-01T08:00:00"),
                    LocalDateTime.parse("2026-01-01T10:00:00"),
                    null,
                    1
                );
        var participant = ExamParticipant.register(exam, 7, LocalDateTime.parse("2026-01-01T07:00:00"));
        participant.enter(LocalDateTime.parse("2026-01-01T08:00:00"));
        given(transactions.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        given(exams.findById(55L)).willReturn(Optional.of(exam));
        given(participants.findByExamIdAndMemberIdAndStatusNot(55L, 7L, ExamParticipantStatus.CANCELED))
            .willReturn(Optional.of(participant));
        given(members.findStatus(7L)).willReturn(Optional.of("ACTIVE"));
        given(problems.findByExamIdAndProblemId(55L, 101L))
            .willReturn(Optional.of(ExamProblem.pin(exam, 101L, 1001L, "문제", 1, new BigDecimal("100.00"), 1)));
        var result = new ExamRunClient.Result(List.of(), "2026-01-01T09:00:00+09:00");
        given(judge.run(any())).willReturn(result);
        // when
        assertThat(service.run("55", "7", "101", new ExamRunService.RunRequest("java", "code", List.of())))
            .isEqualTo(result);
        // then
        var sent = ArgumentCaptor.forClass(ExamRunClient.Request.class);
        verify(judge).run(sent.capture());
        assertThat(sent.getValue().problemRevisionId()).isEqualTo("1001");
        assertThat(sent.getValue().context()).isEqualTo("EXAM");
    }

    /** UTF-8 코드 크기는 원격 호출 전에 제한한다. */
    @Test
    @DisplayName("64KiB를 넘는 코드는 회원 조회나 Judge 호출 전에 거절한다")
    void rejectsOversizeCode() {
        // when & then
        assertThatThrownBy(
            () -> service.run("55", "7", "101", new ExamRunService.RunRequest("java", "가".repeat(21846), List.of()))
        )
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode()).isEqualTo(ContestErrorCode.CODE_TOO_LARGE)
            );
        verifyNoInteractions(refresh, judge);
    }

    /** 취소된 시험은 시작 전이라도 Run을 취소 오류로 차단한다. */
    @Test
    @DisplayName("취소된 시험의 Run은 시작 시각 전에 취소 오류로 거절한다")
    void rejectsCanceledExam() {
        // given
        given(refresh.refresh(7)).willReturn("ACTIVE");
        var exam
            = Exam
                .create(
                    12L,
                    7L,
                    "시험",
                    ExamMode.FIXED,
                    LocalDateTime.parse("2026-01-01T10:00:00"),
                    LocalDateTime.parse("2026-01-01T11:00:00"),
                    null,
                    1
                );
        ReflectionTestUtils.setField(exam, "status", ExamStatus.CANCELED);
        var participant = ExamParticipant.register(exam, 7, LocalDateTime.parse("2026-01-01T07:00:00"));
        given(transactions.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        given(exams.findById(55L)).willReturn(Optional.of(exam));
        given(participants.findByExamIdAndMemberIdAndStatusNot(55L, 7L, ExamParticipantStatus.CANCELED))
            .willReturn(Optional.of(participant));
        // when & then
        assertThatThrownBy(
            () -> service.run("55", "7", "101", new ExamRunService.RunRequest("java", "code", List.of()))
        )
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode()).isEqualTo(ContestErrorCode.EXAM_CANCELED)
            );
        verifyNoInteractions(judge);
    }

    /** 복제본보다 현재 회원 조회가 정지라면 Judge에 요청하지 않는다. */
    @Test
    @DisplayName("현재 회원이 정지 상태이면 활성 복제본이 있어도 Run을 차단한다")
    void rejectsCurrentSuspension() {
        // given
        given(refresh.refresh(7)).willReturn("SUSPENDED");

        // when & then
        assertThatThrownBy(
            () -> service.run("55", "7", "101", new ExamRunService.RunRequest("java", "code", List.of()))
        )
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.MEMBER_SUSPENDED)
            );
        verifyNoInteractions(judge);
    }
}
