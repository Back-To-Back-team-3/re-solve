package com.backtoback.judge.judging.service;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.judge.judging.store.ExternalSubmissionAcceptanceStore;
import com.backtoback.judge.submission.domain.Submission.Context;
import com.backtoback.judge.submission.domain.SubmissionDraft;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/** 시험 접수 원본을 고정 회차의 Judge 작업으로 연결한다. (이벤트 계약 §4.4 C1) */
@Component
@RequiredArgsConstructor
public class ExamSubmissionRequestedHandler implements EventHandler {
    private final ExternalSubmissionAcceptanceStore store;

    /** {@inheritDoc} */
    @Override
    public String eventType() {
        return "ExamSubmissionRequested";
    }

    /** {@inheritDoc} */
    @Override
    public String schemaVersion() {
        return "v1";
    }

    /** {@inheritDoc} */
    @Override
    public void handle(ReceivedEvent event) {
        var payload = event.payload();
        String receiptId = text(payload, "examSubmissionId");
        Assert.isTrue(receiptId.equals(event.metadata().aggregateId()), "시험 접수 ID와 aggregateId가 일치해야 합니다.");
        SubmissionCreateRequest.parseId(text(payload, "participantId"));
        String draftSeq = text(payload, "draftSeq");
        Assert.isTrue(draftSeq.matches("[0-9]{1,19}") && Long.parseLong(draftSeq) >= 0, "Draft 순번이 올바르지 않습니다.");
        String type = text(payload, "submissionType");
        Assert.isTrue("MANUAL".equals(type) || "AUTO".equals(type), "시험 제출 종류가 올바르지 않습니다.");
        String receivedAt = text(payload, "receivedAt");
        Assert
            .isTrue(
                receivedAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}\\+09:00"),
                "시험 접수 시각에는 서울 시간대와 마이크로초가 필요합니다."
            );
        store
            .accept(
                new SubmissionDraft(
                    SubmissionCreateRequest.parseId(text(payload, "userId")),
                    SubmissionCreateRequest.parseId(text(payload, "problemId")),
                    SubmissionCreateRequest.parseId(text(payload, "problemRevisionId")),
                    Context.EXAM,
                    SubmissionCreateRequest.parseId(text(payload, "examId")),
                    SubmissionCreateRequest.parseId(receiptId),
                    null,
                    null,
                    text(payload, "language"),
                    text(payload, "sourceCode"),
                    OffsetDateTime.parse(receivedAt).toLocalDateTime()
                )
            );
    }

    /**
     * 시험 이벤트의 필수 문자열을 읽는다.
     *
     * @param payload 업무 필드
     * @param name 필드 이름
     * @return 문자열 원본
     * @throws IllegalArgumentException 필드가 없거나 문자열이 아닌 경우
     */
    private String text(JsonNode payload, String name) {
        var value = payload.get(name);
        Assert.isTrue(value != null && value.isString(), "시험 이벤트의 필수 문자열이 없습니다: " + name);
        return value.asString();
    }
}
