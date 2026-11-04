package com.backtoback.contest.exam.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.contest.exam.service.ProblemSnapshotService.Snapshot;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 문제 상태 v2를 공통 소비 트랜잭션 안에서 가드 스냅샷에 반영한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>이벤트 계약서 v1.0 / §1.4 소비 규칙·§4.2 problem의 P1 {@code ProblemStateChanged} v2: 소비 경계와 필드</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProblemStateChangedHandler implements EventHandler {
    private final ProblemSnapshotService snapshots;

    /** 처리할 이벤트 종류를 반환한다. */
    @Override
    public String eventType() {
        return "ProblemStateChanged";
    }

    /** 수신 가능한 이벤트 스키마 버전을 반환한다. */
    @Override
    public String schemaVersion() {
        return "v2";
    }

    /** 이벤트의 필수 스냅샷과 식별자를 확인하고 원천 순서대로 저장한다. */
    @Override
    public void handle(ReceivedEvent event) {
        JsonNode data = event.payload();
        long problemId = ReplicaEventHandlers.id(data, "problemId");
        if (!Long.toString(problemId).equals(event.metadata().aggregateId())) {
            throw new IllegalArgumentException("Aggregate identifier does not match problemId");
        }
        long sourceVersion = positiveNumber(data, "sourceVersion");
        String scope = text(data, "scope");
        String visibility = text(data, "visibility");
        String changeType = text(data, "changeType");
        if (!Set.of("VISIBILITY", "REVISION", "LANGUAGE_ADDED").contains(changeType)) {
            throw new IllegalArgumentException("Invalid changeType");
        }
        OffsetDateTime.parse(text(data, "changedAt"));
        var revision = required(data, "problemRevisionId");
        var number = required(data, "revisionNumber");
        Long revisionId = revision.isNull() ? null : ReplicaEventHandlers.id(data, "problemRevisionId");
        Integer revisionNumber = number.isNull() ? null : Math.toIntExact(positiveNumber(data, "revisionNumber"));
        var array = required(data, "supportedLanguages");
        if (!array.isArray()) {
            throw new IllegalArgumentException("Invalid supportedLanguages");
        }
        var languages = new ArrayList<String>();
        for (JsonNode language : array) {
            if (!language.isString()) {
                throw new IllegalArgumentException("Invalid supportedLanguages");
            }
            languages.add(language.asString());
        }
        var operation = data.get("operationId");
        String operationId = operation == null || operation.isNull() ? null : text(data, "operationId");
        snapshots
            .apply(
                new Snapshot(
                    problemId,
                    sourceVersion,
                    scope,
                    visibility,
                    revisionId,
                    revisionNumber,
                    languages,
                    operationId
                )
            );
    }

    /** 필수 필드의 누락과 명시적 null을 구분한다. */
    private JsonNode required(JsonNode data, String field) {
        var value = data.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        return value;
    }

    /** 비어 있지 않은 문자열 필드를 읽는다. */
    private String text(JsonNode data, String field) {
        var value = required(data, field);
        if (!value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Invalid field: " + field);
        }
        return value.asString();
    }

    /** 양의 정수 필드를 손실 없이 읽는다. */
    private long positiveNumber(JsonNode data, String field) {
        var value = required(data, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0) {
            throw new IllegalArgumentException("Invalid field: " + field);
        }
        return value.asLong();
    }
}
