package com.backtoback.member.skill.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.backtoback.common.event.dto.OutboxEvent;
import com.backtoback.common.event.service.OutboxPublishedCallback;
import com.backtoback.member.member.service.MemberEventPublisher;
import com.backtoback.member.skill.store.DiagnosisSubmissionStore;

import lombok.RequiredArgsConstructor;

/**
 * {@code DiagnosisSubmissionRequested}를 SQS로 보낸 뒤 같은 Relay 트랜잭션에서 진단 제출을 {@code REQUESTED}로 바꾼다
 * (이벤트 계약 M6). Relay가 켜진 환경에서만 호출된다.
 */
@Component
@RequiredArgsConstructor
public class DiagnosisSubmissionRequestedCallback implements OutboxPublishedCallback {

    private final DiagnosisSubmissionStore submissionStore;
    private final Clock clock;

    @Override
    public void onPublished(OutboxEvent event) {
        if (MemberEventPublisher.DIAGNOSIS_SUBMISSION_REQUESTED.equals(event.metadata().eventType())) {
            submissionStore.markRequested(Long.parseLong(event.metadata().aggregateId()), LocalDateTime.now(clock));
        }
    }
}
