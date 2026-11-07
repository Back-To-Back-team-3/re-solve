package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.client.ExamContentClient;
import com.backtoback.contest.exam.client.ExamContentClient.Content;
import com.backtoback.contest.exam.client.ExamContentClient.ExecutionLimits;
import com.backtoback.contest.exam.client.ExamContentClient.FunctionSpec;
import com.backtoback.contest.exam.client.ExamContentClient.Sample;
import com.backtoback.contest.exam.domain.ExamParticipantStatus;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamProblemRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/** 시험 입장과 고정 회차를 확인한 뒤 잠금 없이 Problem 본문과 이미지를 조회한다. */
@Service
@RequiredArgsConstructor
public class ExamContentService {
    private static final Pattern IMAGE = Pattern.compile("image:([1-9][0-9]*)");
    private final ExamRepository exams;
    private final ExamProblemRepository problems;
    private final ExamParticipantRepository participants;
    private final ExamContentClient content;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /** 입장자의 고정 문제를 표시 순서대로 변환한다. */
    public ProblemList read(String examId, String memberId) {
        long exam = id(examId);
        long member = id(memberId);
        var snapshot = transactions.execute(status -> snapshot(exam, member));
        var items = snapshot.problems().stream().map(problem -> {
            var source = content.content(exam, problem.problemId(), problem.revisionId());
            var body = body(source);
            return new Problem(
                Long.toString(problem.problemId()),
                Long.toString(problem.revisionId()),
                problem.revisionNumber(),
                problem.title(),
                problem.score(),
                problem.displayOrder(),
                rewrite(body, exam, problem.problemId()),
                source.functionSpec(),
                source.executionLimits(),
                source.samples(),
                imageIds(body)
            );
        }).toList();
        return new ProblemList(examId, snapshot.personalEndsAt(), now(), items);
    }

    /** 본문에 연결된 이미지 ID만 Problem에서 다시 확인하고 전달한다. */
    public ResponseEntity<byte[]> image(String examId, String memberId, String problemId, String imageId) {
        long exam = id(examId);
        long member = id(memberId);
        long problem = id(problemId);
        long image = id(imageId);
        var snapshot = transactions.execute(status -> snapshot(exam, member));
        var pinned
            = snapshot
                .problems()
                .stream()
                .filter(item -> item.problemId() == problem)
                .findFirst()
                .orElseThrow(() -> new ContestException(ContestErrorCode.PROBLEM_NOT_FOUND));
        var source = content.content(exam, problem, pinned.revisionId());
        if (!imageIds(body(source)).contains(Long.toString(image))) {
            throw new ContestException(ContestErrorCode.PROBLEM_NOT_FOUND);
        }
        return content.image(exam, problem, pinned.revisionId(), image);
    }

    /** DB 조회에서 실제 입장과 서버 시작 시각을 확인하고 불변 회차를 복사한다. */
    private Snapshot snapshot(long examId, long memberId) {
        var exam = exams.findById(examId).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        var participant
            = participants
                .findByExamIdAndMemberIdAndStatusNot(examId, memberId, ExamParticipantStatus.CANCELED)
                .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED));
        if (LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).isBefore(exam.getStartsAt())) {
            throw new ContestException(ContestErrorCode.EXAM_NOT_STARTED);
        }
        if (exam.getStatus() == ExamStatus.CANCELED || participant.getEnteredAt() == null) {
            throw new ContestException(ContestErrorCode.EXAM_NOT_REGISTERED);
        }
        var pinned
            = problems
                .findByExamIdOrderByDisplayOrderAsc(examId)
                .stream()
                .map(
                    p -> new Pin(
                        p.getProblemId(),
                        p.getProblemRevisionId(),
                        p.getRevisionNumber(),
                        p.getProblemTitle(),
                        p.getScore().toPlainString(),
                        p.getDisplayOrder()
                    )
                )
                .toList();
        return new Snapshot(
            participant.getPersonalEndsAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
            pinned
        );
    }

    /** 본문 세 부분을 하나의 Markdown으로 조합한다. */
    private String body(Content source) {
        return source.description() + "\n\n" + source.constraints() + "\n\n" + source.examples();
    }

    /** 본문의 이미지 참조를 시험 권한 검증 경로로 바꾼다. */
    private String rewrite(String body, long exam, long problem) {
        return IMAGE.matcher(body).replaceAll("/api/v1/exams/" + exam + "/problems/" + problem + "/images/$1");
    }

    /** 본문에 포함된 이미지 ID를 중복 없이 원래 순서대로 수집한다. */
    private List<String> imageIds(String body) {
        var ids = new LinkedHashSet<String>();
        var matcher = IMAGE.matcher(body);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return List.copyOf(ids);
    }

    /** 현재 서버 시간을 마이크로초 정밀도로 반환한다. */
    private OffsetDateTime now() {
        return OffsetDateTime
            .ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
    }

    /** 양의 BIGINT 식별자만 받는다. */
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

    /** 외부 조회에 필요한 고정 회차와 응답 표시 정보다. */
    private record Pin(
        long problemId,
        long revisionId,
        int revisionNumber,
        String title,
        String score,
        int displayOrder
    ) {
    }
    /** 트랜잭션 안에서 복사한 입장자의 마감과 문제 목록이다. */
    private record Snapshot(OffsetDateTime personalEndsAt, List<Pin> problems) {
    }
    /** 시험 문제 목록과 서버 시간이다. */
    public record ProblemList(
        String examId,
        OffsetDateTime personalEndsAt,
        OffsetDateTime serverNow,
        List<Problem> problems
    ) {
    }
    /** 숨김 자료를 제외한 고정 문제의 공개 필드다. */
    public record Problem(
        String problemId,
        String problemRevisionId,
        int revisionNumber,
        String title,
        String score,
        int displayOrder,
        String body,
        FunctionSpec functionSpec,
        ExecutionLimits executionLimits,
        List<Sample> sampleTests,
        List<String> imageIds
    ) {
    }
}
