package com.backtoback.contest.exam.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamParticipant;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.MemberReplicaRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/** 참가자 잠금 아래 언어 전체의 Draft 순번을 비교해 저장하고 복구한다. */
@Service
@RequiredArgsConstructor
public class ExamDraftStore {
    private static final Map<String, String> LANGUAGES = Map.of("java", "JAVA", "python3", "PYTHON", "cpp", "CPP");
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final ExamProblemRepository problems;
    private final MemberReplicaRepository members;
    private final MemberReplicaService refresh;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /** 입력과 외부 회원 상태를 확인한 뒤 버퍼에 사용할 참가 ID를 조회한다. */
    public Work prepare(String examId, String memberId, String problemId, SaveRequest input) {
        long exam = id(examId);
        long member = id(memberId);
        long problem = id(problemId);
        if (input == null || input.language() == null || input.sourceCode() == null || input.seq() == null) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        String language = LANGUAGES.get(input.language());
        if (language == null) {
            throw new ContestException(ContestErrorCode.UNSUPPORTED_LANGUAGE);
        }
        if (input.sourceCode().getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new ContestException(ContestErrorCode.CODE_TOO_LARGE);
        }
        long seq = id(input.seq());
        refresh.refresh(member);
        var parent = exams.findById(exam).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        var participant
            = participants
                .findByExamIdAndMemberIdAndStatusNot(exam, member, ExamParticipantStatus.CANCELED)
                .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
        var now = localNow();
        validateWindow(parent, participant, now);
        String memberStatus
            = members
                .findStatus(member)
                .orElseThrow(() -> new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE));
        if ("SUSPENDED".equals(memberStatus)) {
            throw new ContestException(ContestErrorCode.MEMBER_SUSPENDED);
        }
        if (!"ACTIVE".equals(memberStatus)) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        if (problems.findByExamIdAndProblemId(exam, problem).isEmpty()) {
            throw new ContestException(ContestErrorCode.EXAM_PROBLEM_NOT_INCLUDED);
        }
        return new Work(exam, member, participant.getId(), problem, language, input.sourceCode(), seq);
    }

    /** 참가자 잠금과 마감 검증 후 최신 순번을 반영하고 커밋 뒤 결과를 반환한다. */
    public SaveResponse save(Work work) {
        return transactions.execute(status -> {
            var parent
                = exams
                    .findForUpdate(work.examId())
                    .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
            var participant
                = participants
                    .findForUpdate(work.examId(), work.memberId())
                    .stream()
                    .filter(row -> row.getId() == work.participantId())
                    .findFirst()
                    .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
            var now = localNow();
            validateWindow(parent, participant, now);
            String memberStatus
                = members
                    .findStatus(work.memberId())
                    .orElseThrow(() -> new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE));
            if ("SUSPENDED".equals(memberStatus)) {
                throw new ContestException(ContestErrorCode.MEMBER_SUSPENDED);
            }
            if (!"ACTIVE".equals(memberStatus)) {
                throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
            }
            if (problems.findByExamIdAndProblemId(work.examId(), work.problemId()).isEmpty()) {
                throw new ContestException(ContestErrorCode.EXAM_PROBLEM_NOT_INCLUDED);
            }
            var current = latest(work.participantId(), work.problemId());
            if (work.seq() <= current.seq()) {
                return response(false, current, participant, now);
            }
            jdbc
                .update(
                    "INSERT INTO exam_drafts (exam_participant_id,problem_id,language,source_code,seq,saved_at,"
                        + "version,created_at,updated_at) VALUES (?,?,?,?,?,?,0,?,?) ON DUPLICATE KEY UPDATE "
                        + "source_code=VALUES(source_code),seq=VALUES(seq),saved_at=VALUES(saved_at),"
                        + "version=version+1,updated_at=VALUES(updated_at)",
                    work.participantId(),
                    work.problemId(),
                    work.language(),
                    work.sourceCode(),
                    work.seq(),
                    now,
                    now,
                    now
                );
            return response(true, new Latest(work.seq(), now), participant, now);
        });
    }

    /** 버퍼에서 밀린 요청은 해당 문제의 DB 순번과 저장 시간을 읽는다. */
    public SaveResponse skipped(Work work) {
        return transactions.execute(status -> {
            var parent
                = exams
                    .findById(work.examId())
                    .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
            var participant
                = participants
                    .findById(work.participantId())
                    .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
            var now = localNow();
            validateWindow(parent, participant, now);
            return response(false, latest(work.participantId(), work.problemId()), participant, now);
        });
    }

    private void validateWindow(Exam exam, ExamParticipant participant, LocalDateTime now) {
        if (exam.getStatus() == ExamStatus.CANCELED) {
            throw new ContestException(ContestErrorCode.EXAM_CANCELED);
        }
        if (now.isBefore(exam.getStartsAt())) {
            throw new ContestException(ContestErrorCode.EXAM_NOT_STARTED);
        }
        if (participant.getEnteredAt() == null) {
            throw new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED);
        }
        if (participant.getStatus() != ExamParticipantStatus.STARTED || now.isAfter(participant.getPersonalEndsAt())
            || now.isAfter(exam.getEndsAt()) || exam.getStatus() == ExamStatus.CLOSED
            || exam.getStatus() == ExamStatus.FINALIZED) {
            throw new ContestException(ContestErrorCode.EXAM_CLOSED);
        }
    }

    /** 종료 후에도 실제 입장자의 문제·언어별 저장본과 문제별 최대 순번을 반환한다. */
    public Recovery read(String examId, String memberId) {
        long exam = id(examId);
        long member = id(memberId);
        return transactions.execute(status -> {
            var parent = exams.findById(exam).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
            var participant
                = participants
                    .findByExamIdAndMemberIdAndStatusNot(exam, member, ExamParticipantStatus.CANCELED)
                    .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
            if (participant.getEnteredAt() == null || localNow().isBefore(parent.getStartsAt())) {
                throw new ContestException(ContestErrorCode.EXAM_NOT_STARTED);
            }
            var items
                = jdbc
                    .query(
                        "SELECT problem_id,language,source_code,seq,saved_at FROM exam_drafts "
                            + "WHERE exam_participant_id=? ORDER BY problem_id,language",
                        (rs, row) -> new Draft(
                            Long.toString(rs.getLong(1)),
                            externalLanguage(rs.getString(2)),
                            rs.getString(3),
                            Long.toString(rs.getLong(4)),
                            offset(rs.getObject(5, LocalDateTime.class))
                        ),
                        participant.getId()
                    );
            var sequences
                = problems
                    .findByExamIdOrderByDisplayOrderAsc(exam)
                    .stream()
                    .map(
                        problem -> new ProblemSequence(
                            problem.getProblemId().toString(),
                            Long.toString(latest(participant.getId(), problem.getProblemId()).seq())
                        )
                    )
                    .toList();
            return new Recovery(
                participant.getId().toString(),
                offset(participant.getPersonalEndsAt()),
                offset(localNow()),
                items,
                sequences
            );
        });
    }

    /** 가장 큰 순번의 저장 시각을 읽고 저장본이 없으면 0과 null을 반환한다. */
    private Latest latest(long participant, long problem) {
        return jdbc
            .query(
                "SELECT seq,saved_at FROM exam_drafts WHERE exam_participant_id=? AND problem_id=? "
                    + "ORDER BY seq DESC LIMIT 1",
                (rs, row) -> new Latest(rs.getLong(1), rs.getObject(2, LocalDateTime.class)),
                participant,
                problem
            )
            .stream()
            .findFirst()
            .orElse(new Latest(0, null));
    }

    /** 저장 결과에 해당 문제의 최신 순번과 서버 시간을 담는다. */
    private SaveResponse response(boolean applied, Latest latest, ExamParticipant participant, LocalDateTime now) {
        return new SaveResponse(
            applied,
            Long.toString(latest.seq()),
            offset(latest.savedAt()),
            offset(participant.getPersonalEndsAt()),
            offset(now)
        );
    }

    /** 저장 언어를 공개 API의 언어 이름으로 변환한다. */
    private String externalLanguage(String language) {
        return switch (language) {
            case "JAVA" -> "java";
            case "PYTHON" -> "python3";
            case "CPP" -> "cpp";
            default -> throw new IllegalStateException("Unknown draft language");
        };
    }

    /** 현재 서버 시간을 DB 정밀도로 반환한다. */
    private LocalDateTime localNow() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
    }

    /** DB 서울 시각을 오프셋이 있는 응답 시각으로 바꾼다. */
    private OffsetDateTime offset(LocalDateTime time) {
        return time == null ? null : time.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
    }

    /** 양의 BIGINT 식별자와 Draft 순번을 검증한다. */
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

    /** 버퍼에 전달할 입력과 검증된 식별자다. */
    public record Work(
        long examId,
        long memberId,
        long participantId,
        long problemId,
        String language,
        String sourceCode,
        long seq
    ) {
    }
    /** Draft 저장 요청이다. */
    public record SaveRequest(String language, String sourceCode, String seq) {
    }
    /** DB에 저장된 최신 순번과 시각이다. */
    private record Latest(long seq, LocalDateTime savedAt) {
    }
    /** 저장 반영 여부와 문제 전체 언어의 최신 순번이다. */
    public record SaveResponse(
        boolean applied,
        String serverSeq,
        OffsetDateTime savedAt,
        OffsetDateTime personalEndsAt,
        OffsetDateTime serverNow
    ) {
    }
    /** 한 언어의 서버 Draft다. */
    public record Draft(String problemId, String language, String sourceCode, String seq, OffsetDateTime savedAt) {
    }
    /** 한 문제의 전체 언어 최대 순번이다. */
    public record ProblemSequence(String problemId, String serverSeq) {
    }
    /** 재진입을 위한 저장본과 문제별 순번이다. */
    public record Recovery(
        String participantId,
        OffsetDateTime personalEndsAt,
        OffsetDateTime serverNow,
        List<Draft> content,
        List<ProblemSequence> problemSequences
    ) {
    }
}
