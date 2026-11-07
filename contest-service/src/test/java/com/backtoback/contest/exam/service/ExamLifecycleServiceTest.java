package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamMode;
import com.backtoback.contest.exam.domain.ExamParticipant;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantHistoryRepository;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamRepository;

/** 시간 경계와 지연된 시험의 상태 전이 및 Outbox 기록을 확인한다. */
@ExtendWith(MockitoExtension.class)
class ExamLifecycleServiceTest {
    @Mock
    private ExamRepository exams;
    @Mock
    private ExamParticipantRepository participants;
    @Mock
    private ExamParticipantHistoryRepository histories;
    @Mock
    private OutboxEventWriter outbox;

    /** 시작과 종료가 모두 지난 경우 두 이벤트와 결시 이력을 남긴다. */
    @Test
    @DisplayName("지연된 예정 시험은 시작과 종료를 순서대로 기록하고 미입장자를 결시 처리한다")
    void closesDelayedExam() {
        // given
        var exam = exam(LocalDateTime.parse("2026-01-01T08:00:00"), LocalDateTime.parse("2026-01-01T08:30:00"));
        var participant = ExamParticipant.register(exam, 7, LocalDateTime.parse("2026-01-01T07:00:00"));
        given(exams.findForUpdate(55L)).willReturn(Optional.of(exam));
        given(participants.findByExamAndStatusForUpdate(55L, ExamParticipantStatus.REGISTERED))
            .willReturn(List.of(participant));
        var service = service();

        // when
        service.transition(55L);

        // then
        assertThat(exam.getStatus()).isEqualTo(ExamStatus.CLOSED);
        assertThat(participant.getStatus()).isEqualTo(ExamParticipantStatus.ABSENT);
        assertThat(participant.getParticipantRevision()).isEqualTo(2);
        verify(histories).save(any());
        var ordered = inOrder(outbox);
        ordered.verify(outbox).append(eq("EXAM"), eq("ExamStarted"), eq("v1"), eq("55"), any(), any());
        ordered.verify(outbox).append(eq("EXAM"), eq("ExamClosed"), eq("v1"), eq("55"), any(), any());
    }

    /** 전체 마감과 같은 시각에는 시작만 하고 종료하지 않는다. */
    @Test
    @DisplayName("전체 마감과 같은 시각에는 시험을 종료하지 않는다")
    void keepsExamOpenAtDeadline() {
        // given
        var exam = exam(LocalDateTime.parse("2026-01-01T08:00:00"), LocalDateTime.parse("2026-01-01T09:00:00"));
        given(exams.findForUpdate(55L)).willReturn(Optional.of(exam));

        // when
        service().transition(55L);

        // then
        assertThat(exam.getStatus()).isEqualTo(ExamStatus.IN_PROGRESS);
        verifyNoInteractions(participants, histories);
        verify(outbox).append(eq("EXAM"), eq("ExamStarted"), eq("v1"), eq("55"), any(), any());
    }

    /** 같은 서버 시간으로 전이 서비스를 만든다. */
    private ExamLifecycleService service() {
        return new ExamLifecycleService(
            exams,
            participants,
            histories,
            outbox,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    /** 지정한 시간과 식별자를 가진 예정 시험을 만든다. */
    private Exam exam(LocalDateTime startsAt, LocalDateTime endsAt) {
        var exam = Exam.create(12L, 7L, "시험", ExamMode.FIXED, startsAt, endsAt, null, 1);
        ReflectionTestUtils.setField(exam, "id", 55L);
        return exam;
    }
}
