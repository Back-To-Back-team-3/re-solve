package com.backtoback.judge.judging.service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.springframework.util.Assert;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.submission.domain.Submission.Context;
import com.backtoback.judge.submission.domain.SubmissionDraft;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;

import tools.jackson.databind.JsonNode;

/** 시험·진단 요청의 공통 계약을 고정 회차 제출 원본으로 변환한다. */
final class ExternalSubmissionRequest {
    /** 이벤트 요청 변환은 인스턴스 상태를 사용하지 않는다. */
    private ExternalSubmissionRequest() {}

    /**
     * 원래 접수와 이벤트 애그리게이트가 일치하는지 확인하고 접수 시각을 보존한다.
     *
     * @param event 공용 제출 큐의 요청
     * @param context 이벤트 종류로 정해진 제출 문맥
     * @param contextField 이벤트의 문맥 ID 필드
     * @param receiptField 이벤트의 접수 ID 필드
     * @return 채점 작업과 함께 저장할 제출 원본
     * @throws IllegalArgumentException 필드가 누락되거나 형식 또는 애그리게이트가 일치하지 않는 경우
     * @throws JudgeException {@code COMMON_INVALID_REQUEST}: ID가 양수 BIGINT 범위의 십진수가 아닌 경우
     * @throws DateTimeParseException 접수 시각이 실제 날짜나 시각으로 해석되지 않는 경우
     */
    static SubmissionDraft parse(ReceivedEvent event, Context context, String contextField, String receiptField) {
        var payload = event.payload();
        String receiptId = text(payload, receiptField);
        Assert.isTrue(receiptId.equals(event.metadata().aggregateId()), "접수 ID와 aggregateId가 일치해야 합니다.");
        String draftSeq = text(payload, "draftSeq");
        Assert.isTrue(draftSeq.matches("[0-9]{1,19}") && Long.parseLong(draftSeq) >= 0, "Draft 순번이 올바르지 않습니다.");
        String type = text(payload, "submissionType");
        Assert.isTrue("MANUAL".equals(type) || "AUTO".equals(type), "제출 종류가 올바르지 않습니다.");
        String receivedAt = text(payload, "receivedAt");
        Assert
            .isTrue(
                receivedAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}\\+09:00"),
                "접수 시각에는 서울 시간대와 마이크로초가 필요합니다."
            );
        return new SubmissionDraft(
            SubmissionCreateRequest.parseId(text(payload, "userId")),
            SubmissionCreateRequest.parseId(text(payload, "problemId")),
            SubmissionCreateRequest.parseId(text(payload, "problemRevisionId")),
            context,
            SubmissionCreateRequest.parseId(text(payload, contextField)),
            SubmissionCreateRequest.parseId(receiptId),
            null,
            null,
            text(payload, "language"),
            text(payload, "sourceCode"),
            OffsetDateTime.parse(receivedAt).toLocalDateTime()
        );
    }

    /**
     * 숫자나 null을 문자열로 바꾸지 않고 필수 문자열을 읽는다.
     *
     * @param payload 이벤트 업무 필드
     * @param name 필드 이름
     * @return 문자열 원본
     * @throws IllegalArgumentException 필드가 없거나 문자열이 아닌 경우
     */
    static String text(JsonNode payload, String name) {
        var value = payload.get(name);
        Assert.isTrue(value != null && value.isString(), "채점 요청의 필수 문자열이 없습니다: " + name);
        return value.asString();
    }
}
