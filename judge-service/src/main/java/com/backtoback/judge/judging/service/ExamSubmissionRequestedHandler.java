package com.backtoback.judge.judging.service;

import org.springframework.stereotype.Component;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.judge.judging.store.ExternalSubmissionAcceptanceStore;
import com.backtoback.judge.submission.domain.Submission.Context;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;

import lombok.RequiredArgsConstructor;

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
        SubmissionCreateRequest.parseId(ExternalSubmissionRequest.text(event.payload(), "participantId"));
        store.accept(ExternalSubmissionRequest.parse(event, Context.EXAM, "examId", "examSubmissionId"));
    }
}
