package com.backtoback.contest.exam.service;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.contest.exam.domain.ExamParticipant;
import com.backtoback.contest.exam.domain.ExamParticipantHistory;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantHistoryRepository;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.service.ExamSubmissionMessageSize.Payload;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/** 개인 마감의 최신 Draft 접수와 참가 종료를 한 트랜잭션으로 처리한다. */
@Service
@RequiredArgsConstructor
public class ExamAutoSubmissionService {
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final ExamProblemRepository problems;
    private final ExamParticipantHistoryRepository histories;
    private final ExamSubmissionMessageSize size;
    private final ExamJudgementService judgements;
    private final EntityManager entityManager;
    private final OutboxEventWriter outbox;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /** 본인 참가자를 잠가 수동 마감과 자동 제출을 즉시 처리한다. */
    public Response finish(String examId, String memberId) {
        long exam = id(examId);
        long member = id(memberId);
        return transactions.execute(status -> {
            var parent
                = exams.findForUpdate(exam).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
            var participant
                = participants
                    .findForUpdate(exam, member)
                    .stream()
                    .filter(p -> p.getStatus() != ExamParticipantStatus.CANCELED)
                    .findFirst()
                    .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
            if (participant.getEnteredAt() == null || parent.getStatus() == ExamStatus.CANCELED) {
                throw new ContestException(ContestErrorCode.EXAM_NOT_STARTED);
            }
            if (participant.getStatus() == ExamParticipantStatus.FINISHED) {
                return response(participant, autoIds(participant.getId()));
            }
            var now = now();
            participant.shortenDeadline(now);
            return response(participant, process(participant, now, member));
        });
    }

    /** 스케줄러 후보의 부모와 참가를 다시 잠가 마감 3초 뒤에만 처리한다. */
    @Transactional
    public boolean finishDue(long examId, long memberId, long participantId) {
        var exam = exams.findForUpdate(examId).orElse(null);
        if (exam == null || exam.getStatus() == ExamStatus.CANCELED) {
            return false;
        }
        var participant
            = participants
                .findForUpdate(examId, memberId)
                .stream()
                .filter(p -> p.getId() == participantId)
                .findFirst()
                .orElse(null);
        var now = now();
        if (participant == null || participant.getStatus() != ExamParticipantStatus.STARTED
            || participant.getEnteredAt() == null || participant.getAutoSubmittedAt() != null
            || participant.getPersonalEndsAt().plusSeconds(3).isAfter(now)) {
            return false;
        }
        process(participant, now, null);
        return true;
    }

    private List<String> process(ExamParticipant participant, LocalDateTime now, Long actor) {
        var drafts
            = jdbc
                .query(
                    "SELECT d.problem_id,d.language,d.source_code,d.seq FROM exam_drafts d "
                        + "WHERE d.exam_participant_id=? AND d.seq=(SELECT MAX(x.seq) FROM exam_drafts x "
                        + "WHERE x.exam_participant_id=d.exam_participant_id AND x.problem_id=d.problem_id) "
                        + "ORDER BY d.problem_id",
                    (rs, row) -> new Draft(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                    participant.getId()
                );
        var ids = new ArrayList<String>();
        var acceptedProblems = new ArrayList<Long>();
        for (var draft : drafts) {
            Long last
                = jdbc
                    .queryForObject(
                        "SELECT COALESCE(MAX(draft_seq),0) FROM exam_submissions "
                            + "WHERE exam_participant_id=? AND problem_id=?",
                        Long.class,
                        participant.getId(),
                        draft.problemId()
                    );
            if (draft.sourceCode().isEmpty() || draft.seq() <= last) {
                continue;
            }
            size.validate(draft.sourceCode());
            long revision
                = problems
                    .findByExamIdAndProblemId(participant.getExam().getId(), draft.problemId())
                    .orElseThrow(() -> new IllegalStateException("Draft problem is not pinned"))
                    .getProblemRevisionId();
            ids.add(append(participant, draft, revision, now));
            acceptedProblems.add(draft.problemId());
        }
        participant.finish(now);
        histories
            .save(
                ExamParticipantHistory
                    .transition(participant, ExamParticipantStatus.STARTED, ExamParticipantStatus.FINISHED, actor, now)
            );
        entityManager.flush();
        for (long problemId : acceptedProblems) {
            judgements.refreshProblem(participant.getExam().getId(), participant.getId(), problemId, now);
        }
        return ids;
    }

    private String append(ExamParticipant participant, Draft draft, long revision, LocalDateTime now) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement
                = connection
                    .prepareStatement(
                        "INSERT INTO exam_submissions "
                            + "(exam_participant_id,exam_id,problem_id,draft_seq,language,source_code,"
                            + "submission_type,received_at,status,is_zero_confirmed,created_at,updated_at) "
                            + "VALUES (?,?,?,?,?,?,'AUTO',?,'ACCEPTED',false,?,?)",
                        Statement.RETURN_GENERATED_KEYS
                    );
            statement.setLong(1, participant.getId());
            statement.setLong(2, participant.getExam().getId());
            statement.setLong(3, draft.problemId());
            statement.setLong(4, draft.seq());
            statement.setString(5, draft.language());
            statement.setString(6, draft.sourceCode());
            statement.setTimestamp(7, Timestamp.valueOf(participant.getPersonalEndsAt()));
            statement.setTimestamp(8, Timestamp.valueOf(now));
            statement.setTimestamp(9, Timestamp.valueOf(now));
            return statement;
        }, keys);
        String receipt = keys.getKey().toString();
        String correlation = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlation == null) {
            correlation = "contest-auto-" + UUID.randomUUID();
        }
        String language = switch (draft.language()) {
            case "JAVA" -> "java";
            case "PYTHON" -> "python3";
            case "CPP" -> "cpp";
            default -> throw new IllegalStateException("Unknown draft language");
        };
        outbox
            .append(
                "EXAM_SUBMISSION",
                "ExamSubmissionRequested",
                "v1",
                receipt,
                correlation,
                new Payload(
                    participant.getExam().getId().toString(),
                    receipt,
                    participant.getId().toString(),
                    participant.getMemberId().toString(),
                    Long.toString(draft.problemId()),
                    Long.toString(revision),
                    language,
                    draft.sourceCode(),
                    Long.toString(draft.seq()),
                    "AUTO",
                    format(participant.getPersonalEndsAt())
                )
            );
        return receipt;
    }

    private List<String> autoIds(long participantId) {
        return jdbc
            .queryForList(
                "SELECT id FROM exam_submissions WHERE exam_participant_id=? "
                    + "AND submission_type='AUTO' ORDER BY id",
                Long.class,
                participantId
            )
            .stream()
            .map(String::valueOf)
            .toList();
    }

    private Response response(ExamParticipant participant, List<String> ids) {
        return new Response(
            participant.getId().toString(),
            participant.getStatus().name(),
            offset(participant.getPersonalEndsAt()),
            offset(participant.getAutoSubmittedAt()),
            ids
        );
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

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
    }

    private String format(LocalDateTime value) {
        return DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx").format(offset(value));
    }

    /** 종료 응답과 자동 접수 식별자다. */
    public record Response(
        String participantId,
        String status,
        OffsetDateTime personalEndsAt,
        OffsetDateTime autoSubmittedAt,
        List<String> autoSubmissionIds
    ) {
    }
    private record Draft(long problemId, String language, String sourceCode, long seq) {
    }
}
