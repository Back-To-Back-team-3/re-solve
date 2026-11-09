package com.backtoback.contest.exam.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.backtoback.common.event.dto.OutboxEvent;
import com.backtoback.common.event.service.OutboxPublishedCallback;

import lombok.RequiredArgsConstructor;

/** 발행 성공한 시험 접수만 조건부로 요청 상태로 바꾼다. */
@Component
@RequiredArgsConstructor
public class ExamSubmissionPublishedCallback implements OutboxPublishedCallback {
    private final JdbcTemplate jdbc;

    /** 먼저 도착한 종결 결과는 덮어쓰지 않는다. */
    @Override
    public void onPublished(OutboxEvent event) {
        if ("ExamSubmissionRequested".equals(event.metadata().eventType())) {
            jdbc
                .update(
                    "UPDATE exam_submissions SET status='REQUESTED',updated_at=CURRENT_TIMESTAMP(6) "
                        + "WHERE id=? AND status='ACCEPTED'",
                    Long.parseLong(event.metadata().aggregateId())
                );
        }
    }
}
