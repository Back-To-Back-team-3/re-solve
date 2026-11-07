package com.backtoback.contest.exam.service;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.contest.exam.client.ExamContentClient;
import com.backtoback.contest.exam.domain.ExamParticipant;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.dto.ExamSubmissionRequest;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.MemberReplicaRepository;
import com.backtoback.contest.exam.service.ExamSubmissionMessageSize.Payload;
import com.backtoback.contest.exam.store.ExamSubmissionRateLimiter;
import com.backtoback.contest.exam.store.ExamSubmissionRequestStore;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** 참가자 잠금으로 직접 제출의 멱등성과 Draft·접수·Outbox 원자성을 보장한다. */
@Service
@RequiredArgsConstructor
public class ExamSubmissionService {
    private static final Map<String, String> LANGUAGES = Map.of("java", "JAVA", "python3", "PYTHON", "cpp", "CPP");
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final ExamProblemRepository problems;
    private final MemberReplicaRepository members;
    private final MemberReplicaService refresh;
    private final ExamContentClient content;
    private final ExamSubmissionRateLimiter limiter;
    private final ExamSubmissionMessageSize messageSize;
    private final ExamJudgementService judgements;
    private final OutboxEventWriter outbox;
    private final ExamSubmissionRequestStore requestStore;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final EntityManager entityManager;

    /** 기존 접수를 먼저 확인하고 신규 요청만 잠금 밖에서 의존 서비스를 조회한다. */
    public Response submit(String examId, String memberId, ExamSubmissionRequest request) {
        long exam = id(examId);
        long member = id(memberId);
        if (request == null || request.language() == null || request.sourceCode() == null) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        long problem = id(request.problemId());
        long seq = id(request.seq());
        var existing = transactions.execute(status -> existingLocked(exam, member, problem, seq, request));
        if (existing != null) {
            return existing;
        }
        String language = LANGUAGES.get(request.language());
        if (language == null) {
            throw new ContestException(ContestErrorCode.UNSUPPORTED_LANGUAGE);
        }
        if (request.sourceCode().isBlank()) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        messageSize.validate(request.sourceCode());
        refresh.refresh(member);
        var pinned
            = problems
                .findByExamIdAndProblemId(exam, problem)
                .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_PROBLEM_NOT_INCLUDED));
        long revision = pinned.getProblemRevisionId();
        if (!content.supportedLanguages(exam, problem, revision).contains(request.language())) {
            throw new ContestException(ContestErrorCode.UNSUPPORTED_LANGUAGE);
        }
        return transactions.execute(status -> accept(exam, member, problem, revision, seq, language, request));
    }

    private Response existingLocked(long exam, long member, long problem, long seq, ExamSubmissionRequest request) {
        exams.findForUpdate(exam).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        var participant = participant(exam, member);
        return existing(participant, problem, seq, request);
    }

    /** 잠금 획득 직후 접수 시간을 기록하고 신규 요청만 저장한다. */
    private Response accept(
        long examId,
        long member,
        long problemId,
        long revision,
        long seq,
        String language,
        ExamSubmissionRequest request
    ) {
        var exam = exams.findForUpdate(examId).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        var participant = participant(examId, member);
        // OSIV에서 앞선 조회의 엔티티가 남아 있어도 부모·참가자 잠금 뒤의 DB 값을 사용한다.
        entityManager.refresh(exam);
        entityManager.refresh(participant);
        var now
            = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
        var old = existing(participant, problemId, seq, request);
        if (old != null) {
            return old;
        }
        if (exam.getStatus() == ExamStatus.CANCELED) {
            throw new ContestException(ContestErrorCode.EXAM_CANCELED);
        }
        if (participant.getStatus() == ExamParticipantStatus.FINISHED) {
            throw new ContestException(ContestErrorCode.EXAM_ALREADY_FINISHED);
        }
        if (participant.getEnteredAt() == null || participant.getStatus() != ExamParticipantStatus.STARTED
            || now.isBefore(exam.getStartsAt()) || now.isAfter(participant.getPersonalEndsAt())
            || now.isAfter(exam.getEndsAt()) || exam.getStatus() == ExamStatus.CLOSED
            || exam.getStatus() == ExamStatus.FINALIZED) {
            throw new ContestException(ContestErrorCode.EXAM_CLOSED);
        }
        String state
            = members
                .findStatus(member)
                .orElseThrow(() -> new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE));
        if ("SUSPENDED".equals(state)) {
            throw new ContestException(ContestErrorCode.MEMBER_SUSPENDED);
        }
        if (!"ACTIVE".equals(state)) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        var pinned
            = problems
                .findByExamIdAndProblemId(examId, problemId)
                .filter(p -> p.getProblemRevisionId() == revision)
                .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_PROBLEM_NOT_INCLUDED));
        var latest
            = jdbc
                .query(
                    "SELECT seq,language,source_code FROM exam_drafts WHERE exam_participant_id=? "
                        + "AND problem_id=? ORDER BY seq DESC LIMIT 1",
                    (rs, row) -> new Draft(rs.getLong(1), rs.getString(2), rs.getString(3)),
                    participant.getId(),
                    problemId
                )
                .stream()
                .findFirst()
                .orElse(null);
        if (latest != null && (seq < latest.seq() || seq == latest.seq()
            && (!language.equals(latest.language()) || !request.sourceCode().equals(latest.sourceCode())))) {
            throw new ContestException(ContestErrorCode.DRAFT_SEQ_CONFLICT);
        }
        limiter.acquire(member, problemId);
        if (latest == null || seq > latest.seq()) {
            jdbc
                .update(
                    "INSERT INTO exam_drafts (exam_participant_id,problem_id,language,source_code,seq,saved_at,"
                        + "version,created_at,updated_at) VALUES (?,?,?,?,?,?,0,?,?) ON DUPLICATE KEY UPDATE "
                        + "source_code=VALUES(source_code),seq=VALUES(seq),saved_at=VALUES(saved_at),"
                        + "version=version+1,updated_at=VALUES(updated_at)",
                    participant.getId(),
                    problemId,
                    language,
                    request.sourceCode(),
                    seq,
                    now,
                    now,
                    now
                );
        }
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement
                = connection
                    .prepareStatement(
                        "INSERT INTO exam_submissions "
                            + "(exam_participant_id,exam_id,problem_id,draft_seq,language,source_code,"
                            + "submission_type,received_at,status,is_zero_confirmed,created_at,updated_at) "
                            + "VALUES (?,?,?,?,?,?,'MANUAL',?,'ACCEPTED',false,?,?)",
                        Statement.RETURN_GENERATED_KEYS
                    );
            statement.setLong(1, participant.getId());
            statement.setLong(2, examId);
            statement.setLong(3, problemId);
            statement.setLong(4, seq);
            statement.setString(5, language);
            statement.setString(6, request.sourceCode());
            statement.setTimestamp(7, Timestamp.valueOf(now));
            statement.setTimestamp(8, Timestamp.valueOf(now));
            statement.setTimestamp(9, Timestamp.valueOf(now));
            return statement;
        }, keys);
        long receipt = keys.getKey().longValue();
        judgements.refreshProblem(examId, participant.getId(), problemId, now);
        String correlation = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlation == null) {
            correlation = "contest-" + UUID.randomUUID();
        }
        var metadata
            = outbox
                .append(
                    "EXAM_SUBMISSION",
                    "ExamSubmissionRequested",
                    "v1",
                    Long.toString(receipt),
                    correlation,
                    new Payload(
                        Long.toString(examId),
                        Long.toString(receipt),
                        participant.getId().toString(),
                        Long.toString(member),
                        Long.toString(problemId),
                        pinned.getProblemRevisionId().toString(),
                        request.language(),
                        request.sourceCode(),
                        Long.toString(seq),
                        "MANUAL",
                        format(now)
                    )
                );
        requestStore.preserve(receipt, metadata.eventId());
        return new Response(
            Long.toString(receipt),
            "ACCEPTED",
            offset(now),
            Long.toString(seq),
            offset(participant.getPersonalEndsAt())
        );
    }

    private Response existing(ExamParticipant participant, long problem, long seq, ExamSubmissionRequest request) {
        return jdbc
            .query(
                "SELECT id,language,source_code,status,received_at FROM exam_submissions "
                    + "WHERE exam_participant_id=? AND problem_id=? AND draft_seq=?",
                (rs, row) -> {
                    if (!rs.getString(2).equals(LANGUAGES.get(request.language()))
                        || !rs.getString(3).equals(request.sourceCode())) {
                        throw new ContestException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                    }
                    String status = rs.getString(4);
                    if ("JUDGED".equals(status) && participant.getExam().getStatus() != ExamStatus.CLOSED
                        && participant.getExam().getStatus() != ExamStatus.FINALIZED) {
                        status = "REQUESTED";
                    }
                    return new Response(
                        Long.toString(rs.getLong(1)),
                        status,
                        offset(rs.getObject(5, LocalDateTime.class)),
                        Long.toString(seq),
                        offset(participant.getPersonalEndsAt())
                    );
                },
                participant.getId(),
                problem,
                seq
            )
            .stream()
            .findFirst()
            .orElse(null);
    }

    private ExamParticipant participant(long exam, long member) {
        return participants
            .findForUpdate(exam, member)
            .stream()
            .filter(p -> p.getStatus() != ExamParticipantStatus.CANCELED)
            .findFirst()
            .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
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

    private OffsetDateTime offset(LocalDateTime value) {
        return value.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
    }

    private String format(LocalDateTime value) {
        return DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx").format(offset(value));
    }

    /** 접수 결과다. */
    public record Response(
        String examSubmissionId,
        String status,
        OffsetDateTime receivedAt,
        String draftSeq,
        OffsetDateTime personalEndsAt
    ) {
    }
    private record Draft(long seq, String language, String sourceCode) {
    }
}
