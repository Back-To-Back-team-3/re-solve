package com.backtoback.member.skill.event;

import org.springframework.stereotype.Component;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.member.skill.service.DiagnosisScoringService;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * judge의 {@code DiagnosisSubmissionJudged} v1(이벤트 계약 J5)을 진단 제출에 반영하고, 응시 내 마지막 종결이면 결과를 확정한다.
 */
@Component
@RequiredArgsConstructor
public class DiagnosisSubmissionJudgedHandler implements EventHandler {

    private final DiagnosisScoringService scoringService;

    @Override
    public String eventType() {
        return "DiagnosisSubmissionJudged";
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
        int judgeAttempt = EventFields.integer(data, "judgeAttempt");
        if (!"DIAGNOSIS".equals(EventFields.text(data, "context"))
            || !"COMPLETED".equals(EventFields.text(data, "status")) || !EventFields.VERDICTS.contains(verdict)
            || judgeAttempt < 1) {
            throw new IllegalArgumentException("Invalid context, status, verdict or judgeAttempt");
        }
        scoringService
            .applyResult(
                EventFields.id(data, "diagnosisId"),
                EventFields.id(data, "diagnosisSubmissionId"),
                EventFields.id(data, "userId"),
                EventFields.id(data, "problemId"),
                submissionId,
                judgeAttempt,
                DiagnosisScoringService.JUDGED,
                verdict
            );
    }
}
