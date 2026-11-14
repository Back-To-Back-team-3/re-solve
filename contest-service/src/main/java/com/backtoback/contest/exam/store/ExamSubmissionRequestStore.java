package com.backtoback.contest.exam.store;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** 최초 Outbox 메시지를 시험 접수와 함께 불변 보존한다 (ERD 4.4.6). */
@Component
@RequiredArgsConstructor
public class ExamSubmissionRequestStore {
    private final JdbcTemplate jdbc;

    /** 최초 기록에 실패하면 접수와 Outbox를 함께 롤백한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void preserve(long receiptId, UUID eventId) {
        int changed
            = jdbc
                .update(
                    "UPDATE exam_submissions s JOIN outbox_events o ON o.event_id=? "
                        + "SET s.request_snapshot=o.payload WHERE s.id=? AND s.request_snapshot IS NULL "
                        + "AND o.event_type='ExamSubmissionRequested' AND o.aggregate_type='EXAM_SUBMISSION' "
                        + "AND o.aggregate_id=CAST(s.id AS CHAR)",
                    eventId.toString(),
                    receiptId
                );
        if (changed != 1) {
            throw new IllegalStateException("Original request could not be preserved");
        }
    }
}
