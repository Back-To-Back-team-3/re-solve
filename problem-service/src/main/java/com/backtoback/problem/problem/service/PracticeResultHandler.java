package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.common.time.ClockConfiguration;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 일반 제출 종결 이벤트(J1)를 공통 소비 트랜잭션에서 회원·문제 집계에 반영한다. */
@Component
@RequiredArgsConstructor
public class PracticeResultHandler implements EventHandler {
    private static final Set<String> VERDICTS = Set.of("AC", "WA", "TLE", "MLE", "RE", "CE");
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** {@inheritDoc} */
    @Override
    public String eventType() {
        return "SubmissionJudged";
    }

    /** {@inheritDoc} */
    @Override
    public String schemaVersion() {
        return "v1";
    }

    /** {@inheritDoc} */
    @Override
    public void handle(ReceivedEvent event) {
        var result = Result.from(event);
        lockAggregates(result);
        var previous
            = jdbc
                .query(
                    """
                        SELECT member_id, problem_id, submitted_at, judge_attempt, verdict
                        FROM problem_submission_results
                        WHERE submission_id=? FOR UPDATE
                        """,
                    (row, index) -> new Result(
                        result.submission(),
                        row.getLong("member_id"),
                        row.getLong("problem_id"),
                        row.getObject("submitted_at", LocalDateTime.class),
                        row.getInt("judge_attempt"),
                        row.getString("verdict")
                    ),
                    result.submission()
                )
                .stream()
                .findFirst();
        var now = currentTime();
        int firstSubmission = previous.isEmpty() ? 1 : 0;
        int acceptedDelta = result.accepted() ? 1 : 0;
        if (previous.isPresent()) {
            var old = previous.get();
            if (old.member() != result.member() || old.problem() != result.problem()
                || !old.submitted().equals(result.submitted())) {
                throw new IllegalArgumentException("제출의 회원·문제·접수 시각이 기존 기록과 다릅니다.");
            }
            int updated = jdbc.update("""
                UPDATE problem_submission_results SET judge_attempt=?, verdict=?, updated_at=?
                WHERE submission_id=? AND judge_attempt < ?
                """, result.attempt(), result.verdict(), now, result.submission(), result.attempt());
            if (updated == 0) {
                return;
            }
            acceptedDelta -= old.accepted() ? 1 : 0;
        } else {
            jdbc
                .update(
                    """
                        INSERT INTO problem_submission_results
                        (submission_id, member_id, problem_id, submitted_at,
                         judge_attempt, verdict, created_at, updated_at)
                        VALUES (?,?,?,?,?,?,?,?)
                        """,
                    result.submission(),
                    result.member(),
                    result.problem(),
                    result.submitted(),
                    result.attempt(),
                    result.verdict(),
                    now,
                    now
                );
        }
        jdbc.update("""
            UPDATE problem_statistics SET submission_count=submission_count+?,
            accepted_submission_count=accepted_submission_count+?, updated_at=? WHERE problem_id=?
            """, firstSubmission, acceptedDelta, now, result.problem());
        // MySQL 단일 테이블 UPDATE는 앞의 대입부터 적용하므로 시각을 바꾸기 전에 최초 AC 제출을 선택한다.
        jdbc
            .update(
                """
                    UPDATE member_problem_statuses SET attempt_count=attempt_count+?, is_solved=is_solved OR ?,
                    first_solved_submission_id=IF(
                        ? AND (first_solved_at IS NULL OR first_solved_at>?),?,first_solved_submission_id),
                    first_solved_at=IF(? AND (first_solved_at IS NULL OR first_solved_at>?),?,first_solved_at),
                    updated_at=? WHERE member_id=? AND problem_id=?
                    """,
                firstSubmission,
                result.accepted(),
                result.accepted(),
                result.submitted(),
                result.submission(),
                result.accepted(),
                result.submitted(),
                result.submitted(),
                now,
                result.member(),
                result.problem()
            );
    }

    /**
     * 통계와 회원 집계를 순서대로 잠그고 없는 행은 생성한다. 최초 생성 경합은 소비 트랜잭션 재전달에 맡긴다.
     *
     * @param result 수신 결과
     */
    private void lockAggregates(Result result) {
        if (jdbc
            .queryForList(
                "SELECT problem_id FROM problem_statistics WHERE problem_id=? FOR UPDATE",
                Long.class,
                result.problem()
            )
            .isEmpty()) {
            jdbc.update("""
                INSERT INTO problem_statistics (problem_id, submission_count,
                    accepted_submission_count, updated_at)
                VALUES (?,0,0,?)
                """, result.problem(), currentTime());
        }
        if (jdbc
            .queryForList(
                "SELECT id FROM member_problem_statuses WHERE member_id=? AND problem_id=? FOR UPDATE",
                Long.class,
                result.member(),
                result.problem()
            )
            .isEmpty()) {
            var createdAt = currentTime();
            jdbc.update("""
                INSERT INTO member_problem_statuses (member_id,problem_id,is_solved,attempt_count,created_at,updated_at)
                VALUES (?,?,false,0,?,?)
                """, result.member(), result.problem(), createdAt, createdAt);
        }
    }

    /**
     * 서비스 기준의 현재 시각을 조회한다.
     *
     * @return DB 기록 시각
     */
    private LocalDateTime currentTime() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
    }

    /** 제출의 불변 식별 정보와 해당 채점 회차의 최종 판정이다. */
    private record Result(
        long submission,
        long member,
        long problem,
        LocalDateTime submitted,
        int attempt,
        String verdict
    ) {
        /**
         * Problem이 소비하는 필드를 검사하고 서비스 기준 시각으로 변환한다.
         *
         * @param event 공통 헤더 검증을 마친 J1 이벤트
         * @return 제출 반영 정보
         * @throws IllegalArgumentException 필수 필드·판정·식별자가 유효하지 않을 때
         */
        static Result from(ReceivedEvent event) {
            var payload = event.payload();
            long submission = id(payload, "submissionId");
            var attempt = payload.path("judgeAttempt");
            var verdict = payload.path("verdict").asString("");
            if (!Long.toString(submission).equals(event.metadata().aggregateId()) || !attempt.isIntegralNumber()
                || !attempt.canConvertToInt() || attempt.asInt() < 1 || !VERDICTS.contains(verdict)) {
                throw new IllegalArgumentException("일반 제출의 식별자·회차·판정이 유효하지 않습니다.");
            }
            var submitted
                = OffsetDateTime
                    .parse(payload.path("submittedAt").asString(""))
                    .atZoneSameInstant(ClockConfiguration.SERVICE_ZONE)
                    .toLocalDateTime();
            if (submitted.getNano() % 1000 != 0) {
                throw new IllegalArgumentException("제출 접수 시각이 마이크로초 정밀도를 초과합니다.");
            }
            return new Result(
                submission,
                id(payload, "userId"),
                id(payload, "problemId"),
                submitted,
                attempt.asInt(),
                verdict
            );
        }

        /**
         * 문자열 형식의 양수 식별자를 읽는다.
         *
         * @param payload 업무 필드
         * @param field 식별자 필드 이름
         * @return 식별자
         * @throws IllegalArgumentException 식별자의 형식이 잘못된 경우
         */
        private static long id(JsonNode payload, String field) {
            var value = payload.path(field);
            if (!value.isString() || !value.asString().matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException("유효하지 않은 식별자: " + field);
            }
            return Long.parseLong(value.asString());
        }

        /**
         * 해당 채점 회차의 통과 여부를 반환한다.
         *
         * @return AC 여부
         */
        boolean accepted() {
            return "AC".equals(verdict);
        }
    }
}
