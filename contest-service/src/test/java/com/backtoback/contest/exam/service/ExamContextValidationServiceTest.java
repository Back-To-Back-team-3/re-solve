package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamMode;
import com.backtoback.contest.exam.domain.ExamParticipant;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamProblem;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.dto.ContextValidateRequest;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.MemberReplicaRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 고정 회차·입장·서버 시간과 회원 상태별 내부 권한을 확인한다. */
@ExtendWith(MockitoExtension.class)
class ExamContextValidationServiceTest {
    @Mock
    private ExamRepository exams;
    @Mock
    private ExamProblemRepository problems;
    @Mock
    private ExamParticipantRepository participants;
    @Mock
    private MemberReplicaRepository members;
    @Mock
    private JdbcTemplate jdbc;
    private ExamContextValidationService service;
    private Exam exam;
    private ExamParticipant participant;

    /** 실제 입장한 참가와 고정 회차를 준비한다. */
    @BeforeEach
    void setUp() {
        service
            = new ExamContextValidationService(
                exams,
                problems,
                participants,
                members,
                jdbc,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
            );
        exam
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
        participant = ExamParticipant.register(exam, 7, LocalDateTime.parse("2026-01-01T07:00:00"));
        participant.enter(LocalDateTime.parse("2026-01-01T08:00:00"));
        ReflectionTestUtils.setField(participant, "id", 411L);
        var problem = ExamProblem.pin(exam, 101L, 1001L, "문제", 1, new BigDecimal("100.00"), 1);
        given(exams.findById(55L)).willReturn(Optional.of(exam));
        given(problems.findByExamIdAndProblemId(55L, 101L)).willReturn(Optional.of(problem));
    }

    /** 종료 후에도 입장자의 고정 회차 본문은 복기할 수 있다. */
    @Test
    @DisplayName("종료한 시험의 입장자는 고정 회차 본문을 복기할 수 있다")
    void readsAfterClose() {
        // given
        ReflectionTestUtils.setField(exam, "status", ExamStatus.CLOSED);
        ReflectionTestUtils.setField(participant, "status", ExamParticipantStatus.FINISHED);
        given(participants.findByExamIdAndMemberIdAndStatusNot(55L, 7L, ExamParticipantStatus.CANCELED))
            .willReturn(Optional.of(participant));
        // when
        var response = service.validate(request("READ_BODY", "1001"));
        // then
        assertThat(response.allowed()).isTrue();
        assertThat(response.resultVisibility()).isEqualTo("PROVISIONAL");
        assertThat(response.problemRevisionId()).isEqualTo("1001");
    }

    /** 요청 회차가 고정 회차와 다르면 참가 여부 확인 전에 거절한다. */
    @Test
    @DisplayName("고정되지 않은 회차의 본문 열람은 거절한다")
    void rejectsOtherRevision() {
        // when & then
        assertThatThrownBy(() -> service.validate(request("READ_BODY", "1002")))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.PROBLEM_REVISION_NOT_INCLUDED)
            );
    }

    /** 입장하지 않았거나 정지된 참가자는 Run을 실행하지 못한다. */
    @Test
    @DisplayName("정지 회원의 시험 Run은 거절한다")
    void rejectsSuspendedRun() {
        // given
        given(participants.findByExamIdAndMemberIdAndStatusNot(55L, 7L, ExamParticipantStatus.CANCELED))
            .willReturn(Optional.of(participant));
        given(members.findStatus(7L)).willReturn(Optional.of("SUSPENDED"));
        // when & then
        assertThatThrownBy(() -> service.validate(request("RUN", "1001")))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.CONTEXT_ACCESS_DENIED)
            );
    }

    /** Judge가 요청한 Run은 활성 복제본과 고정 회차가 있어야 허용한다. */
    @Test
    @DisplayName("활성 복제본의 고정 회차 Run 문맥을 허용한다")
    void allowsActiveRun() {
        // given
        given(participants.findByExamIdAndMemberIdAndStatusNot(55L, 7L, ExamParticipantStatus.CANCELED))
            .willReturn(Optional.of(participant));
        given(members.findStatus(7L)).willReturn(Optional.of("ACTIVE"));

        // when & then
        assertThat(service.validate(request("RUN", "1001")).allowed()).isTrue();
    }

    /** 회원 복제본이 아직 없으면 내부 Run 문맥을 허용하지 않는다. */
    @Test
    @DisplayName("회원 복제본이 없으면 Judge Run 문맥을 차단한다")
    void rejectsRunWithoutReplica() {
        // given
        given(participants.findByExamIdAndMemberIdAndStatusNot(55L, 7L, ExamParticipantStatus.CANCELED))
            .willReturn(Optional.of(participant));
        given(members.findStatus(7L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.validate(request("RUN", "1001")))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.CONTEXT_ACCESS_DENIED)
            );
    }

    /** 접수 ID와 참가·문제 연결이 모두 일치할 때만 열람을 허용한다. */
    @Test
    @DisplayName("본인 접수만 시험 제출 열람을 허용한다")
    void readsOwnReceipt() {
        // given
        given(participants.findByExamIdAndMemberIdAndStatusNot(55L, 7L, ExamParticipantStatus.CANCELED))
            .willReturn(Optional.of(participant));
        given(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM exam_submissions WHERE id=? AND exam_id=? AND exam_participant_id=? "
                        + "AND problem_id=?",
                    Integer.class,
                    4001L,
                    55L,
                    411L,
                    101L
                )
        ).willReturn(1, 0);
        var request = new ContextValidateRequest("EXAM", "55", "7", "101", "1001", "READ_SUBMISSION", "4001");
        // when & then
        assertThat(service.validate(request).allowed()).isTrue();
        assertThatThrownBy(() -> service.validate(request))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.CONTEXT_ACCESS_DENIED)
            );
    }

    /** 지정한 행위와 회차의 내부 검증 요청을 만든다. */
    private ContextValidateRequest request(String action, String revision) {
        return new ContextValidateRequest("EXAM", "55", "7", "101", revision, action, null);
    }
}
