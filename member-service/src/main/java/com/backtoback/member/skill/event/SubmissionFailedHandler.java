package com.backtoback.member.skill.event;

import org.springframework.stereotype.Component;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.member.skill.service.DiagnosisScoringService;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * judge의 {@code SubmissionFailed} v1(이벤트 계약 J4) 중 진단 제출만 {@code FAILED}로 반영한다(쿨타임 면제). 다른 문맥은 소비만
 * 하고 무시한다.
 */
@Component
@RequiredArgsConstructor
public class SubmissionFailedHandler implements EventHandler {

    private final DiagnosisScoringService scoringService;

    @Override
    public String eventType() {
        return "SubmissionFailed";
    }

    @Override
    public String schemaVersion() {
        return "v1";
    }

    @Override
    public void handle(ReceivedEvent event) {
        JsonNode data = event.payload();
        if (!"DIAGNOSIS".equals(EventFields.text(data, "context"))) {
            return;
        }
        long submissionId = EventFields.id(data, "submissionId");
        int judgeAttempt = EventFields.integer(data, "judgeAttempt");
        if (!String.valueOf(submissionId).equals(event.metadata().aggregateId())
            || !"FAILED".equals(EventFields.text(data, "status")) || judgeAttempt < 1) {
            throw new IllegalArgumentException("Invalid aggregateId, status or judgeAttempt");
        }
        scoringService
            .applyResult(
                EventFields.id(data, "contextId"),
                EventFields.id(data, "receiptId"),
                EventFields.id(data, "userId"),
                EventFields.id(data, "problemId"),
                submissionId,
                judgeAttempt,
                DiagnosisScoringService.FAILED,
                null
            );
    }
}
