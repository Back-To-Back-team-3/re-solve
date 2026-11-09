package com.backtoback.contest.exam.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.OutboxEvent;

/** Relay 콜백이 시험 접수만 조건부로 전이하는지 확인한다. */
@ExtendWith(MockitoExtension.class)
class ExamSubmissionPublishedCallbackTest {
    @Mock
    private JdbcTemplate jdbc;

    /** 이미 결과가 도착한 접수는 갱신하지 않는 조건을 DB에 전달한다. */
    @Test
    @DisplayName("시험 접수 발행은 ACCEPTED 상태만 REQUESTED로 변경한다")
    void changesOnlyAccepted() {
        // when
        new ExamSubmissionPublishedCallback(jdbc).onPublished(event("ExamSubmissionRequested"));
        // then
        verify(jdbc)
            .update(
                "UPDATE exam_submissions SET status='REQUESTED',updated_at=CURRENT_TIMESTAMP(6) "
                    + "WHERE id=? AND status='ACCEPTED'",
                4001L
            );
    }

    /** 다른 이벤트의 발행은 시험 접수를 건드리지 않는다. */
    @Test
    @DisplayName("다른 이벤트 발행은 시험 접수를 변경하지 않는다")
    void ignoresOtherEvents() {
        // when
        new ExamSubmissionPublishedCallback(jdbc).onPublished(event("ExamClosed"));
        // then
        verifyNoInteractions(jdbc);
    }

    /** 발행 성공 콜백에 전달할 저장 이벤트를 만든다. */
    private OutboxEvent event(String type) {
        return new OutboxEvent(
            1,
            "EXAM_SUBMISSION",
            new EventMetadata(
                UUID.randomUUID(),
                type,
                "v1",
                OffsetDateTime.parse("2026-01-01T09:00:00+09:00"),
                "4001",
                "test"
            ),
            "{}",
            0
        );
    }
}
