package com.backtoback.contest.operation.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;

import lombok.RequiredArgsConstructor;

/** 스터디 종료 이벤트로 동일한 준비 작업의 완료를 복구한다 (이벤트 계약서 §4.5 S4). */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class StudyClosedHandler {
    private final ContestGuardWorkflowService workflow;

    @Bean
    EventHandler studyClosedEventHandler() {
        return new EventHandler() {
            public String eventType() {
                return "StudyClosed";
            }

            public String schemaVersion() {
                return "v1";
            }

            public void handle(ReceivedEvent event) {
                var payload = event.payload();
                var id = payload.get("studyId");
                var version = payload.get("sourceVersion");
                var operation = payload.get("operationId");
                var closedAt = payload.get("closedAt");
                if (id == null || !id.isString() || !id.asString().matches("[1-9][0-9]*")
                    || !id.asString().equals(event.metadata().aggregateId()) || version == null
                    || !version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() <= 0
                    || closedAt == null || !closedAt.isString()
                    || operation != null && !operation.isNull() && (!operation.isString()
                        || operation.asString().isBlank() || operation.asString().length() > 100)) {
                    throw new IllegalArgumentException("Invalid study closed event");
                }
                java.time.OffsetDateTime.parse(closedAt.asString());
                long studyId = Long.parseLong(id.asString());
                workflow
                    .studyClosed(
                        studyId,
                        operation == null || operation.isNull() ? null : operation.asString(),
                        version.asLong()
                    );
            }
        };
    }
}
