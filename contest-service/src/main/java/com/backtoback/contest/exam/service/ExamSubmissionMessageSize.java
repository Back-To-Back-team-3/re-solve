package com.backtoback.contest.exam.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.service.EventJsonCodec;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;

/** 시험 Draft가 최대 길이의 접수 이벤트에도 원문 그대로 들어가는지 확인한다. */
@Component
@RequiredArgsConstructor
public class ExamSubmissionMessageSize {
    private static final int MAX_CODE_BYTES = 65536;
    private static final int MAX_EVENT_BYTES = 262144;
    private static final String MAX_ID = "9223372036854775807";
    private static final String MAX_TIME = "9999-12-31T23:59:59.999999+09:00";
    private final EventJsonCodec codec;

    /** 공통 직렬화 결과와 원문을 각각 UTF-8 바이트로 검사한다. */
    public void validate(String sourceCode) {
        if (sourceCode.getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES) {
            throw new ContestException(ContestErrorCode.CODE_TOO_LARGE);
        }
        // BIGINT 양수 19자리와 공통 메타데이터의 64자 추적 ID가 JSON에서 확장되는 최대 길이를 사용한다.
        var metadata
            = new EventMetadata(
                UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff"),
                "ExamSubmissionRequested",
                "v1",
                OffsetDateTime.parse(MAX_TIME),
                MAX_ID,
                String.valueOf((char)1).repeat(64)
            );
        var payload
            = new Payload(
                MAX_ID,
                MAX_ID,
                MAX_ID,
                MAX_ID,
                MAX_ID,
                MAX_ID,
                "python3",
                sourceCode,
                MAX_ID,
                "MANUAL",
                MAX_TIME
            );
        if (codec.serialize(metadata, payload).getBytes(StandardCharsets.UTF_8).length > MAX_EVENT_BYTES) {
            throw new ContestException(ContestErrorCode.CODE_TOO_LARGE);
        }
    }

    /** 이벤트 계약의 업무 필드이며 공통 헤더는 직렬화기가 합친다. */
    public record Payload(
        String examId,
        String examSubmissionId,
        String participantId,
        String userId,
        String problemId,
        String problemRevisionId,
        String language,
        String sourceCode,
        String draftSeq,
        String submissionType,
        String receivedAt
    ) {
    }
}
