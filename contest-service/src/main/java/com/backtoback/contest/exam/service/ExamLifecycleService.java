package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamParticipantHistory;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantHistoryRepository;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamRepository;

import lombok.RequiredArgsConstructor;

/**
 * 시험별 잠금과 트랜잭션 안에서 시작·종료 및 결시 이력을 저장한다.
 * <p>기준 문서</p>
 * <ul><li>스케줄러 v1.0 / §4.1 시험 시작·종료: 시각 재검증과 전이</li>
 * <li>이벤트 계약서 v1.0 / §4.4 contest: 시작·종료 Outbox</li></ul>
 */
@Service
@RequiredArgsConstructor
public class ExamLifecycleService {
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final ExamParticipantHistoryRepository histories;
    private final OutboxEventWriter outbox;
    private final Clock clock;

    /** 부모 잠금 후 시각과 상태를 재확인하고 필요한 전이를 모두 기록한다. */
    @Transactional
    public void transition(long examId) {
        Exam exam = exams.findForUpdate(examId).orElse(null);
        if (exam == null) {
            return;
        }
        LocalDateTime now
            = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
        String correlation = "sched-exam-lifecycle-" + UUID.randomUUID();
        if (exam.getStatus() == ExamStatus.SCHEDULED && !now.isBefore(exam.getStartsAt())) {
            exam.start();
            outbox
                .append(
                    "EXAM",
                    "ExamStarted",
                    "v1",
                    exam.getId().toString(),
                    correlation,
                    new Started(
                        exam.getId().toString(),
                        exam.getStudyId().toString(),
                        Integer.toString(exam.getExamRevision()),
                        format(exam.getStartsAt()),
                        format(exam.getEndsAt())
                    )
                );
        }
        if (exam.getStatus() == ExamStatus.IN_PROGRESS && now.isAfter(exam.getEndsAt())) {
            exam.close(now);
            for (var participant : participants
                .findByExamAndStatusForUpdate(examId, ExamParticipantStatus.REGISTERED)) {
                participant.markAbsent();
                histories
                    .save(
                        ExamParticipantHistory
                            .transition(
                                participant,
                                ExamParticipantStatus.REGISTERED,
                                ExamParticipantStatus.ABSENT,
                                null,
                                now
                            )
                    );
            }
            outbox
                .append(
                    "EXAM",
                    "ExamClosed",
                    "v1",
                    exam.getId().toString(),
                    correlation,
                    new Closed(
                        exam.getId().toString(),
                        exam.getStudyId().toString(),
                        Integer.toString(exam.getExamRevision()),
                        format(now)
                    )
                );
        }
    }

    /** 서비스 시간대의 이벤트 시각을 마이크로초와 오프셋을 포함해 변환한다. */
    private String format(LocalDateTime time) {
        return DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx")
            .format(time.atZone(ClockConfiguration.SERVICE_ZONE));
    }

    /** 시험 시작 시점의 고정 설정을 전달한다. */
    public record Started(String examId, String studyId, String examRevision, String startsAt, String endsAt) {
    }

    /** 시험 종료 전이의 시각과 설정 버전을 전달한다. */
    public record Closed(String examId, String studyId, String examRevision, String closedAt) {
    }
}
