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
        long submissionId = EventFields.id(data, "submissionId");
        if (!String.valueOf(submissionId).equals(event.metadata().aggregateId())) {
            throw new IllegalArgumentException("aggregateId does not match submissionId");
        }
        String verdict = EventFields.text(data, "verdict");
        int difficulty = EventFields.integer(data, "problemDifficulty");
        int judgeAttempt = EventFields.integer(data, "judgeAttempt");
        if (!EventFields.VERDICTS.contains(verdict) || difficulty < 1 || difficulty > 3 || judgeAttempt < 1) {
            throw new IllegalArgumentException("Invalid verdict, problemDifficulty or judgeAttempt");
        }
        JsonNode tags = data.get("problemTagIds");
        if (tags == null || !tags.isArray() || tags.isEmpty()) {
            throw new IllegalArgumentException("problemTagIds must be a non-empty array");
        }
        Set<Long> tagIds = new LinkedHashSet<>();
        tags.forEach(tag -> tagIds.add(EventFields.parseId(tag, "problemTagIds")));
        practiceResultService
            .apply(
                new PracticeResult(
                    submissionId,
                    EventFields.id(data, "userId"),
                    EventFields.id(data, "problemId"),
                    difficulty,
                    verdict,
                    judgeAttempt,
                    OffsetDateTime
                        .parse(EventFields.text(data, "submittedAt"))
                        .atZoneSameInstant(ClockConfiguration.SERVICE_ZONE)
                        .toLocalDateTime(),
                    tagIds
                )
            );
    }
}
