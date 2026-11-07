package com.backtoback.contest.exam.service;

import java.time.OffsetDateTime;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.contest.exam.service.ExamJudgementService.Result;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 시험 채점 완료와 실패 이벤트를 공통 소비 트랜잭션에 연결한다.
 * <p>기준 문서</p>
 * <ul><li>이벤트 계약서 v1.0 / §4.3 judge의 J2·J4: 채점 결과와 실패</li></ul>
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class ExamJudgementHandlers {
    private final ExamJudgementService results;

    /** 시험 채점 완료 이벤트를 등록한다. */
    @Bean
    EventHandler examJudgedHandler() {
        return handler("ExamSubmissionJudged", false);
    }

    /** 실패 이벤트 중 시험 문맥만 반영한다. */
    @Bean
    EventHandler submissionFailedHandler() {
        return handler("SubmissionFailed", true);
    }

    /** 이벤트 종류별 입력을 공통 결과 처리로 전달한다. */
    private EventHandler handler(String type, boolean failed) {
        return new EventHandler() {
            /** 이벤트 종류를 반환한다. */
            public String eventType() {
                return type;
            }

            /** 지원하는 이벤트 버전이다. */
            public String schemaVersion() {
                return "v1";
            }

            /** 문맥과 필수 필드를 확인하고 시험 결과를 반영한다. */
            public void handle(ReceivedEvent event) {
                var data = event.payload();
                String context = text(data, "context");
                if (failed && !"EXAM".equals(context)) {
                    return;
                }
                if (!"EXAM".equals(context) || !text(data, "status").equals(failed ? "FAILED" : "COMPLETED")) {
                    throw new IllegalArgumentException("Invalid result context or status");
                }
                long submission = ReplicaEventHandlers.id(data, "submissionId");
                if (!Long.toString(submission).equals(event.metadata().aggregateId())) {
                    throw new IllegalArgumentException("Aggregate identifier mismatch");
                }
                int attempt = number(data, "judgeAttempt");
                if (attempt < 1) {
                    throw new IllegalArgumentException("Invalid judge attempt");
                }
                String verdict = failed ? null : text(data, "verdict");
                Integer passed = failed ? null : number(data, "passedCount");
                Integer total = failed ? null : number(data, "totalCount");
                if (failed) {
                    text(data, "errorCode");
                } else if (!Set.of("AC", "WA", "TLE", "MLE", "RE", "CE").contains(verdict) || total <= 0 || passed < 0
                    || passed > total) {
                    throw new IllegalArgumentException("Invalid verdict or counts");
                }
                if (!failed) {
                    Integer time = optionalNumber(data, "maxTimeMs");
                    Integer memory = optionalNumber(data, "maxMemoryKb");
                    if (time != null && time < 0 || memory != null && memory < 0
                        || "CE".equals(verdict) && (time != null || memory != null)) {
                        throw new IllegalArgumentException("Invalid execution metrics");
                    }
                }
                results
                    .apply(
                        new Result(
                            ReplicaEventHandlers.id(data, failed ? "contextId" : "examId"),
                            ReplicaEventHandlers.id(data, failed ? "receiptId" : "examSubmissionId"),
                            submission,
                            ReplicaEventHandlers.id(data, "userId"),
                            ReplicaEventHandlers.id(data, "problemId"),
                            ReplicaEventHandlers.id(data, "problemRevisionId"),
                            attempt,
                            failed ? "FAILED" : "JUDGED",
                            verdict,
                            passed,
                            total,
                            OffsetDateTime.parse(text(data, "submittedAt"))
                        )
                    );
            }
        };
    }

    /** 공백이 아닌 필수 문자열을 읽는다. */
    private String text(JsonNode payload, String field) {
        var value = payload.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Invalid field: " + field);
        }
        return value.asString();
    }

    /** 손실 없이 표현 가능한 정수 필드를 읽는다. */
    private int number(JsonNode payload, String field) {
        var value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("Invalid field: " + field);
        }
        return value.asInt();
    }

    /** 선택 측정값은 null 또는 정수만 받는다. */
    private Integer optionalNumber(JsonNode payload, String field) {
        var value = payload.get(field);
        return value == null || value.isNull() ? null : number(payload, field);
    }
}
