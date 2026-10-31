package com.backtoback.judge.judging.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.judge.judging.dto.ProblemValidationRequest;
import com.backtoback.judge.judging.store.ValidationAcceptanceStore;

import lombok.RequiredArgsConstructor;

/** 검증 요청을 소비 기록과 함께 저장하며 실행은 DB 작업 실행기에 맡긴다. */
@Component
@RequiredArgsConstructor
public class ProblemValidationRequestedHandler implements EventHandler {
    private final ValidationAcceptanceStore store;

    /** {@inheritDoc} */
    @Override
    public String eventType() {
        return "ProblemValidationRequested";
    }

    /** {@inheritDoc} */
    @Override
    public String schemaVersion() {
        return "v1";
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(ReceivedEvent event) {
        store.accept(ProblemValidationRequest.from(event), event.metadata().correlationId());
    }
}
