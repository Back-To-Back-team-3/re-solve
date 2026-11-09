package com.backtoback.member.skill.event;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.member.skill.service.PracticeResult;
import com.backtoback.member.skill.service.PracticeResultService;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * judge의 {@code SubmissionJudged} v1(이벤트 계약 J1, {@code PRACTICE})을 연습 결과 복제본과 성취도에 반영한다.
 * 필드가 계약과 다르면 예외로 재전달·DLQ에 맡긴다.
 */
@Component
@RequiredArgsConstructor
public class SubmissionJudgedHandler implements EventHandler {

    private static final Set<String> VERDICTS = Set.of("AC", "WA", "TLE", "MLE", "RE", "CE");

    private final PracticeResultService practiceResultService;

    @Override
    public String eventType() {
        return "SubmissionJudged";
    }

    @Override
    public String schemaVersion() {
        return "v1";
    }

    @Override
    public void handle(ReceivedEvent event) {
        JsonNode data = event.payload();
        long submissionId = id(data, "submissionId");
        if (!String.valueOf(submissionId).equals(event.metadata().aggregateId())) {
            throw new IllegalArgumentException("aggregateId does not match submissionId");
        }
        String verdict = text(data, "verdict");
        int difficulty = integer(data, "problemDifficulty");
        int judgeAttempt = integer(data, "judgeAttempt");
        if (!VERDICTS.contains(verdict) || difficulty < 1 || difficulty > 3 || judgeAttempt < 1) {
            throw new IllegalArgumentException("Invalid verdict, problemDifficulty or judgeAttempt");
        }
        JsonNode tags = data.get("problemTagIds");
        if (tags == null || !tags.isArray() || tags.isEmpty()) {
            throw new IllegalArgumentException("problemTagIds must be a non-empty array");
        }
        Set<Long> tagIds = new LinkedHashSet<>();
        tags.forEach(tag -> tagIds.add(parseId(tag, "problemTagIds")));
        practiceResultService
            .apply(
                new PracticeResult(
                    submissionId,
                    id(data, "userId"),
                    id(data, "problemId"),
                    difficulty,
                    verdict,
                    judgeAttempt,
                    OffsetDateTime
                        .parse(text(data, "submittedAt"))
                        .atZoneSameInstant(ClockConfiguration.SERVICE_ZONE)
                        .toLocalDateTime(),
                    tagIds
                )
            );
    }

    private static String text(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return value.asString();
    }

    private static int integer(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("Invalid field: " + field);
        }
        return value.asInt();
    }

    private static long id(JsonNode data, String field) {
        JsonNode value = data.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return parseId(value, field);
    }

    // 계약상 ID는 숫자 문자열이다.
    private static long parseId(JsonNode value, String field) {
        if (!value.isString() || !value.asString().matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Invalid id: " + field);
        }
        return Long.parseLong(value.asString());
    }
}
