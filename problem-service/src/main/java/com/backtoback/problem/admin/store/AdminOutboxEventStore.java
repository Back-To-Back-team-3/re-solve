package com.backtoback.problem.admin.store;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AdminOutboxEventStore {

    private final JdbcTemplate jdbc;

    public Optional<OutboxEventRow> findById(long id) {
        var rows
            = jdbc
                .query(
                    "SELECT id, status, occurred_at FROM outbox_events WHERE id = ?",
                    (rs, row) -> new OutboxEventRow(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getTimestamp("occurred_at").toLocalDateTime()
                    ),
                    id
                );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public boolean resetFailedToPending(long id, LocalDateTime now) {
        return jdbc.update("""
            UPDATE outbox_events
            SET status = 'PENDING', retry_count = 0, updated_at = ?
            WHERE id = ? AND status = 'FAILED'
            """, Timestamp.valueOf(now), id) > 0;
    }

    public record OutboxEventRow(long id, String status, LocalDateTime occurredAt) {
    }
}
