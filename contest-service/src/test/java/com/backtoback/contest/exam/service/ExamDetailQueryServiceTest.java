package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamMode;
import com.backtoback.contest.exam.domain.ExamParticipant;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.StudyMembershipReplicaRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 시험 상세의 접근 자격과 응답에 담을 본인 참가를 확인한다. */
@ExtendWith(MockitoExtension.class)
class ExamDetailQueryServiceTest {
    @Mock
    private ExamRepository exams;
    @Mock
    private ExamParticipantRepository participants;
    @Mock
    private ExamProblemRepository problems;
    @Mock
    private StudyMembershipReplicaRepository memberships;
    @Mock
    private Clock clock;
    @InjectMocks
    private ExamQueryService service;

    /** 탈퇴한 참가자의 등록 상태와 서버 시각을 상세에 반영한다. */
    @Test
    @DisplayName("탈퇴한 활성 참가자는 입장 기록을 변경하지 않고 상세를 조회한다")
    void departedParticipantCanRead() {
        // given
        Exam exam = exam();
        ExamParticipant participant = mock(ExamParticipant.class);
        given(exams.findById(55L)).willReturn(Optional.of(exam));
        given(participants.findByExamIdAndMemberIdAndStatusNot(55L, 7L, ExamParticipantStatus.CANCELED))
            .willReturn(Optional.of(participant));
        given(participant.getId()).willReturn(411L);
        given(participant.getStatus()).willReturn(ExamParticipantStatus.REGISTERED);
        given(participant.getPersonalEndsAt()).willReturn(LocalDateTime.of(2026, 11, 7, 11, 0));
        given(clock.instant()).willReturn(Instant.parse("2026-11-07T00:00:00Z"));
        given(problems.countByExamId(55L)).willReturn(3L);
        // when
        var response = service.detail("55", "7");
        // then
        assertThat(response.examId()).isEqualTo("55");
        assertThat(response.totalScore()).isEqualTo("100.00");
        assertThat(response.examRevision()).isEqualTo("1");
        assertThat(response.version()).isEqualTo("0");
        assertThat(response.resultRevision()).isEqualTo("0");
        assertThat(response.problemCount()).isEqualTo(3);
        assertThat(response.serverNow().toString()).isEqualTo("2026-11-07T09:00+09:00");
        assertThat(response.myParticipation().participantId()).isEqualTo("411");
        assertThat(response.myParticipation().enteredAt()).isNull();
        verify(participant, never()).enter(any());
        verifyNoInteractions(memberships);
    }

    /** 승인된 구성원은 활성 참가가 없어도 상세를 조회한다. */
    @Test
    @DisplayName("현재 구성원은 활성 참가가 없으면 참가 정보 없이 조회한다")
    void memberCanReadWithoutParticipation() {
        // given
        Exam exam = exam();
        given(exams.findById(55L)).willReturn(Optional.of(exam));
        given(memberships.isActive(12, 7)).willReturn(true);
        given(clock.instant()).willReturn(Instant.parse("2026-11-07T00:00:00Z"));
        // when
        var response = service.detail("55", "7");
        // then
        assertThat(response.myParticipation()).isNull();
    }

    /** 시작과 종료 경계의 조회는 상태를 시간으로 추정하거나 입장을 기록하지 않는다. */
    @Test
    @DisplayName("시작과 종료 시각에도 저장된 시험 상태와 서버 시간을 반환한다")
    void readsStoredStateAtTimeBoundaries() {
        // given
        Exam exam = exam();
        given(exams.findById(55L)).willReturn(Optional.of(exam));
        given(memberships.isActive(12, 7)).willReturn(true);
        given(clock.instant()).willReturn(Instant.parse("2026-11-07T01:00:00Z"), Instant.parse("2026-11-07T02:00:00Z"));
        // when
        var atStart = service.detail("55", "7");
        var atEnd = service.detail("55", "7");
        // then
        assertThat(atStart.serverNow()).isEqualTo(atStart.startsAt());
        assertThat(atEnd.serverNow()).isEqualTo(atEnd.endsAt());
        assertThat(atStart.status()).isEqualTo("SCHEDULED");
        assertThat(atEnd.status()).isEqualTo("SCHEDULED");
    }

    /** 탈퇴자에게 취소 이력만 있으면 활성 참가 조회는 비어 접근이 거절된다. */
    @Test
    @DisplayName("활성 참가가 없는 탈퇴자의 상세 조회를 거절한다")
    void deniesWithoutActiveParticipation() {
        // given
        Exam exam = mock(Exam.class);
        given(exam.getStudyId()).willReturn(12L);
        given(exams.findById(55L)).willReturn(Optional.of(exam));
        // when & then
        assertThatThrownBy(() -> service.detail("55", "7"))
            .isInstanceOf(ContestException.class)
            .extracting("errorCode")
            .isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED);
        verifyNoInteractions(problems, clock);
    }

    /** 존재하지 않는 시험은 자격 조회보다 먼저 404로 구분한다. */
    @Test
    @DisplayName("존재하지 않는 시험은 시험 없음 오류를 반환한다")
    void missingExam() {
        // when & then
        assertThatThrownBy(() -> service.detail("55", "7"))
            .isInstanceOf(ContestException.class)
            .extracting("errorCode")
            .isEqualTo(ContestErrorCode.EXAM_NOT_FOUND);
        verifyNoInteractions(participants, memberships, problems, clock);
    }

    /** 상세 DTO 변환에 필요한 저장 시험을 준비한다. */
    private Exam exam() {
        Exam exam = mock(Exam.class);
        given(exam.getId()).willReturn(55L);
        given(exam.getStudyId()).willReturn(12L);
        given(exam.getMode()).willReturn(ExamMode.FIXED);
        given(exam.getStatus()).willReturn(ExamStatus.SCHEDULED);
        given(exam.getStartsAt()).willReturn(LocalDateTime.of(2026, 11, 7, 10, 0));
        given(exam.getEndsAt()).willReturn(LocalDateTime.of(2026, 11, 7, 11, 0));
        given(exam.getTotalScore()).willReturn(new BigDecimal("100.00"));
        given(exam.getExamRevision()).willReturn(1);
        return exam;
    }
}
