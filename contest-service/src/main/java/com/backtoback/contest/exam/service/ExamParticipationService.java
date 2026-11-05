package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamParticipant;
import com.backtoback.contest.exam.domain.ExamParticipantHistory;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.dto.ExamCancellationResponse;
import com.backtoback.contest.exam.dto.ExamEntryResponse;
import com.backtoback.contest.exam.dto.ExamParticipationResponse;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.Resource;
import com.backtoback.contest.exam.repository.ExamParticipantHistoryRepository;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.MemberReplicaRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/** 시험 참가의 상태와 이력, 이벤트를 원자적으로 저장한다 (API 명세서 4.2). */
@Service
@RequiredArgsConstructor
public class ExamParticipationService {
    private final MemberReplicaService memberRefresh;
    private final MemberReplicaRepository members;
    private final ContestResourceGuardService guards;
    private final ExamEligibilityService eligibility;
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final ExamParticipantHistoryRepository histories;
    private final OutboxEventWriter outbox;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /** 원격 회원 조회는 자원 잠금 전에 수행한다 (ERD 6.4.1). */
    public Registration register(String examId, String memberId) {
        long exam = id(examId);
        long member = id(memberId);
        memberRefresh.refresh(member);
        return transactions.execute(status -> registerLocked(exam, member));
    }

    private Registration registerLocked(long examId, long member) {
        guards.lockForEnrollment(List.of(new Resource("MEMBER", member)));
        requireActive(member);
        Exam exam = lockExam(examId);
        var rows = participants.findForUpdate(examId, member);
        var now = now();
        if (exam.getStatus() == ExamStatus.CANCELED) {
            throw new ContestException(ContestErrorCode.EXAM_CANCELED);
        }
        if (!now.isBefore(exam.getEndsAt())) {
            throw new ContestException(ContestErrorCode.EXAM_CLOSED);
        }
        eligibility.requireApprovedMembership(exam.getStudyId(), member);
        var active = rows.stream().filter(row -> row.getStatus() != ExamParticipantStatus.CANCELED).findFirst();
        if (active.isPresent()) {
            return new Registration(ExamParticipationResponse.from(active.get(), now), false);
        }
        var participant = participants.saveAndFlush(ExamParticipant.register(exam, member, now));
        history(participant, null, ExamParticipantStatus.REGISTERED, member, now);
        var problemIds
            = jdbc
                .queryForList(
                    "SELECT problem_id FROM exam_problems WHERE exam_id = ? " + "ORDER BY display_order",
                    Long.class,
                    examId
                )
                .stream()
                .map(String::valueOf)
                .toList();
        append(
            "ExamParticipantRegistered",
            participant,
            new RegisteredPayload(
                "EXAM",
                Long.toString(examId),
                Long.toString(examId),
                participant.getId().toString(),
                Long.toString(member),
                Long.toString(participant.getParticipantRevision()),
                Integer.toString(exam.getExamRevision()),
                problemIds,
                format(exam.getStartsAt()),
                format(exam.getEndsAt())
            )
        );
        return new Registration(ExamParticipationResponse.from(participant, now), true);
    }

    /** 취소된 마지막 참가의 재요청은 기존 결과를 돌려준다 (API 명세서 4.2.10). */
    public ExamCancellationResponse cancel(String examId, String memberId) {
        long exam = id(examId);
        long member = id(memberId);
        return transactions.execute(status -> {
            var parent = lockExam(exam);
            var rows = participants.findForUpdate(exam, member);
            var participant
                = rows
                    .stream()
                    .filter(row -> row.getStatus() != ExamParticipantStatus.CANCELED)
                    .findFirst()
                    .orElseGet(
                        () -> rows
                            .stream()
                            .findFirst()
                            .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED))
                    );
            if (participant.getStatus() == ExamParticipantStatus.CANCELED) {
                return ExamCancellationResponse.from(participant);
            }
            var now = now();
            if (!now.isBefore(parent.getStartsAt()) || participant.getStatus() != ExamParticipantStatus.REGISTERED) {
                throw new ContestException(ContestErrorCode.EXAM_PARTICIPATION_CANCELLATION_CLOSED);
            }
            participant.cancel();
            history(participant, ExamParticipantStatus.REGISTERED, ExamParticipantStatus.CANCELED, member, now);
            append(
                "ExamParticipantCanceled",
                participant,
                new CanceledPayload(
                    "EXAM",
                    Long.toString(exam),
                    Long.toString(exam),
                    participant.getId().toString(),
                    Long.toString(member),
                    Long.toString(participant.getParticipantRevision()),
                    Integer.toString(parent.getExamRevision()),
                    format(now)
                )
            );
            return ExamCancellationResponse.from(participant);
        });
    }

    /** 원격 회원 조회를 잠금 전에 끝내고 최초 입장과 탈퇴 준비를 직렬화한다 (ERD 4.4.16). */
    public ExamEntryResponse enter(String examId, String memberId) {
        long exam = id(examId);
        long member = id(memberId);
        memberRefresh.refresh(member);
        return transactions.execute(status -> {
            guards.lockForEnrollment(List.of(new Resource("MEMBER", member)));
            requireActive(member);
            var parent = lockExam(exam);
            var participant
                = participants
                    .findForUpdate(exam, member)
                    .stream()
                    .filter(row -> row.getStatus() != ExamParticipantStatus.CANCELED)
                    .findFirst()
                    .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
            var now = now();
            if (now.isBefore(parent.getStartsAt())) {
                throw new ContestException(ContestErrorCode.EXAM_NOT_STARTED);
            }
            if (participant.getStatus() == ExamParticipantStatus.FINISHED) {
                throw new ContestException(ContestErrorCode.EXAM_ALREADY_FINISHED);
            }
            if (now.isAfter(participant.getPersonalEndsAt()) || parent.getStatus() == ExamStatus.CANCELED
                || participant.getStatus() == ExamParticipantStatus.ABSENT) {
                throw new ContestException(ContestErrorCode.EXAM_CLOSED);
            }
            if (participant.getStatus() == ExamParticipantStatus.REGISTERED) {
                participant.enter(now);
                history(participant, ExamParticipantStatus.REGISTERED, ExamParticipantStatus.STARTED, member, now);
            }
            return ExamEntryResponse.from(participant, now);
        });
    }

    private void requireActive(long member) {
        String state
            = members
                .findStatus(member)
                .orElseThrow(() -> new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE));
        if ("SUSPENDED".equals(state)) {
            throw new ContestException(ContestErrorCode.MEMBER_SUSPENDED);
        }
        if (!"ACTIVE".equals(state)) {
            throw new ContestException(ContestErrorCode.CONTEST_OPERATION_BLOCKED);
        }
    }

    private Exam lockExam(long id) {
        return exams.findForUpdate(id).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
    }

    private void history(
        ExamParticipant participant,
        ExamParticipantStatus from,
        ExamParticipantStatus to,
        long member,
        LocalDateTime now
    ) {
        histories.save(ExamParticipantHistory.transition(participant, from, to, member, now));
    }

    private void append(String type, ExamParticipant participant, Object payload) {
        String correlation = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlation == null) {
            correlation = "contest-" + UUID.randomUUID();
        }
        outbox.append("EXAM_PARTICIPANT", type, "v1", participant.getId().toString(), correlation, payload);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
    }

    private String format(LocalDateTime time) {
        return DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx")
            .format(time.atZone(ClockConfiguration.SERVICE_ZONE));
    }

    private long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]*")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    /** 등록 응답과 생성 여부다. */
    public record Registration(ExamParticipationResponse response, boolean created) {
    }

    /** 등록 시점의 시험 전체 설정 스냅샷이다 (이벤트 계약서 4.4 C3). */
    public record RegisteredPayload(
        String context,
        String contextId,
        String examId,
        String participantId,
        String memberId,
        String participantRevision,
        String contextRevision,
        List<String> problemIds,
        String startsAt,
        String endsAt
    ) {
    }

    /** 취소한 참가와 설정 버전이다 (이벤트 계약서 4.4 C5). */
    public record CanceledPayload(
        String context,
        String contextId,
        String examId,
        String participantId,
        String memberId,
        String participantRevision,
        String contextRevision,
        String canceledAt
    ) {
    }
}
