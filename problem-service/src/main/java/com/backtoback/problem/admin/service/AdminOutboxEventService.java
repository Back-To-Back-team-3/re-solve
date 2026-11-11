package com.backtoback.problem.admin.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.problem.admin.store.AdminOutboxEventStore;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminOutboxEventService {

    private static final int REPROCESS_WINDOW_DAYS = 7;

    private final AdminOutboxEventStore store;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public long reprocess(long outboxEventId, long actorId, String reason, String clientIp) {
        var now = LocalDateTime.now(clock);
        var row
            = store
                .findById(outboxEventId)
                .filter(r -> r.occurredAt().isAfter(now.minusDays(REPROCESS_WINDOW_DAYS)))
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.OUTBOX_EVENT_NOT_FOUND));

        if (!"FAILED".equals(row.status())) {
            throw new ProblemException(ProblemErrorCode.OUTBOX_EVENT_NOT_REPROCESSABLE);
        }

        store.resetFailedToPending(outboxEventId, now);

        jdbc.update("""
            INSERT INTO audit_logs(actor_id, action, target_type, target_id, reason, client_ip, created_at)
            VALUES (?, 'OUTBOX_REPROCESS', 'OUTBOX_EVENT', ?, ?, ?, ?)
            """, actorId, Long.toString(outboxEventId), reason, clientIp, now);

        return outboxEventId;
    }
}
