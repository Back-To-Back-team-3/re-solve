package com.backtoback.judge.judging.service;

import org.springframework.stereotype.Component;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.judge.judging.store.ExternalSubmissionAcceptanceStore;
import com.backtoback.judge.submission.domain.Submission.Context;

import lombok.RequiredArgsConstructor;

/** Member가 접수한 진단의 고정 회차를 채점 작업으로 연결한다. (이벤트 계약 §4.1 M6) */
@Component
@RequiredArgsConstructor
public class DiagnosisSubmissionRequestedHandler implements EventHandler {
    private final ExternalSubmissionAcceptanceStore store;

    /** {@inheritDoc} */
    @Override
    public String eventType() {
        return "DiagnosisSubmissionRequested";
    }

    /** {@inheritDoc} */
    @Override
    public String schemaVersion() {
        return "v1";
    }

    /** {@inheritDoc} */
    @Override
    public void handle(ReceivedEvent event) {
        store.accept(ExternalSubmissionRequest.parse(event, Context.DIAGNOSIS, "diagnosisId", "diagnosisSubmissionId"));
    }
}
