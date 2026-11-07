package com.backtoback.contest.exam.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.client.ExamRunClient;
import com.backtoback.contest.exam.client.ExamRunClient.CustomTest;
import com.backtoback.contest.exam.client.ExamRunClient.Result;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.repository.MemberReplicaRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/** 입장·회원 상태와 고정 회차를 확인한 뒤 Judge Run을 호출한다. */
@Service
@RequiredArgsConstructor
public class ExamRunService {
    private final ExamRepository exams;
    private final ExamProblemRepository problems;
    private final ExamParticipantRepository participants;
    private final MemberReplicaRepository members;
    private final MemberReplicaService refresh;
    private final ExamRunClient judge;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /** 입력을 검증하고 원격 회원 조회 후 실제 고정 회차로 실행한다. */
    public Result run(String examId, String memberId, String problemId, RunRequest request) {
        long exam = id(examId);
        long member = id(memberId);
        long problem = id(problemId);
        if (request == null || request.language() == null || request.sourceCode() == null
            || request.sourceCode().isEmpty() || request.customTests() != null && (request.customTests().size() > 10
                || request.customTests().stream().anyMatch(test -> test == null || test.args() == null))) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        if (!Set.of("java", "python3", "cpp").contains(request.language())) {
            throw new ContestException(ContestErrorCode.UNSUPPORTED_LANGUAGE);
        }
        if (request.sourceCode().getBytes(StandardCharsets.UTF_8).length > 65536) {
            throw new ContestException(ContestErrorCode.CODE_TOO_LARGE);
        }
        refresh.refresh(member);
        long revision = transactions.execute(status -> validate(exam, member, problem));
        return judge
            .run(
                new ExamRunClient.Request(
                    "EXAM",
                    examId,
                    memberId,
                    problemId,
                    Long.toString(revision),
                    request.language(),
                    request.sourceCode(),
                    request.customTests()
                )
            );
    }

    /** DB에서 현재 회원과 참가 상태 및 요청 시각을 확인한다. */
    private long validate(long examId, long memberId, long problemId) {
        var exam = exams.findById(examId).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        var participant
            = participants
                .findByExamIdAndMemberIdAndStatusNot(examId, memberId, ExamParticipantStatus.CANCELED)
                .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
        var now = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
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
        String memberStatus
            = members
                .findStatus(memberId)
                .orElseThrow(() -> new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE));
        if ("SUSPENDED".equals(memberStatus)) {
            throw new ContestException(ContestErrorCode.MEMBER_SUSPENDED);
        }
        if (!"ACTIVE".equals(memberStatus)) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        return problems
            .findByExamIdAndProblemId(examId, problemId)
            .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_PROBLEM_NOT_INCLUDED))
            .getProblemRevisionId();
    }

    /** 양의 BIGINT 식별자를 검증한다. */
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

    /** 공개 예제 실행을 위한 코드와 사용자 입력이다. */
    public record RunRequest(String language, String sourceCode, List<CustomTest> customTests) {
    }
}
