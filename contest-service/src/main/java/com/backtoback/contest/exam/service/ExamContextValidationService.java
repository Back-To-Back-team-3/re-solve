package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.dto.ContextValidateRequest;
import com.backtoback.contest.exam.dto.ContextValidationResponse;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.MemberReplicaRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/**
 * 실제 시험·고정 회차·입장 상태와 접수 소유권으로 내부 접근 권한을 판정한다.
 * <p>기준 문서</p>
 * <ul><li>API 명세서 v1.1 / §4.7.1 실행 문맥·열람 권한 검증: EXAM 접근 조건</li></ul>
 */
@Service
@RequiredArgsConstructor
public class ExamContextValidationService {
    private final ExamRepository exams;
    private final ExamProblemRepository problems;
    private final ExamParticipantRepository participants;
    private final MemberReplicaRepository members;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** EXAM 열람과 Run을 검증하고 본인 접수의 연결 관계를 확인한다. */
    @Transactional(readOnly = true)
    public ContextValidationResponse validate(ContextValidateRequest request) {
        if (!"EXAM".equals(request.context())) {
            throw new ContestException(ContestErrorCode.CONTEXT_ACCESS_DENIED);
        }
        long examId = id(request.contextId());
        long memberId = id(request.memberId());
        long problemId = id(request.problemId());
        long revisionId = id(request.problemRevisionId());
        var exam = exams.findById(examId).orElseThrow(() -> new ContestException(ContestErrorCode.CONTEXT_NOT_FOUND));
        var problem
            = problems
                .findByExamIdAndProblemId(examId, problemId)
                .filter(value -> value.getProblemRevisionId() == revisionId)
                .orElseThrow(() -> new ContestException(ContestErrorCode.PROBLEM_REVISION_NOT_INCLUDED));
        var participant
            = participants
                .findByExamIdAndMemberIdAndStatusNot(examId, memberId, ExamParticipantStatus.CANCELED)
                .orElseThrow(() -> new ContestException(ContestErrorCode.CONTEXT_ACCESS_DENIED));
        OffsetDateTime now
            = OffsetDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
        LocalDateTime localNow = now.toLocalDateTime();
        if (participant.getEnteredAt() == null || localNow.isBefore(exam.getStartsAt())
            || exam.getStatus() == ExamStatus.CANCELED) {
            throw new ContestException(ContestErrorCode.CONTEXT_ACCESS_DENIED);
        }
        if ("READ_SUBMISSION".equals(request.action())) {
            long receiptId = id(request.receiptId());
            Integer count
                = jdbc
                    .queryForObject(
                        "SELECT COUNT(*) FROM exam_submissions WHERE id=? AND exam_id=? AND exam_participant_id=? "
                            + "AND problem_id=?",
                        Integer.class,
                        receiptId,
                        examId,
                        participant.getId(),
                        problemId
                    );
            if (count == null || count != 1) {
                throw new ContestException(ContestErrorCode.CONTEXT_ACCESS_DENIED);
            }
        }
        if ("RUN".equals(request.action())) {
            if (participant.getStatus() != ExamParticipantStatus.STARTED
                || localNow.isAfter(participant.getPersonalEndsAt()) || localNow.isAfter(exam.getEndsAt())
                || exam.getStatus() == ExamStatus.CLOSED || exam.getStatus() == ExamStatus.FINALIZED) {
                throw new ContestException(ContestErrorCode.EXAM_CLOSED);
            }
            if (!"ACTIVE".equals(members.findStatus(memberId).orElse(null))) {
                throw new ContestException(ContestErrorCode.CONTEXT_ACCESS_DENIED);
            }
        }
        String visibility = switch (exam.getStatus()) {
            case FINALIZED -> "FINALIZED";
            case CLOSED -> "PROVISIONAL";
            default -> "SAMPLES_ONLY";
        };
        return new ContextValidationResponse(
            true,
            participant.getId().toString(),
            problem.getProblemRevisionId().toString(),
            visibility,
            participant.getPersonalEndsAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
            now
        );
    }

    /** 양의 BIGINT 문자열만 내부 식별자로 받는다. */
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
}
