package com.backtoback.ai.hint.consumer;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * processed_events INSERT 전담 컴포넌트 (event-contract.md §1.4 소비 규칙).
 * REQUIRES_NEW로 별도 트랜잭션을 열어 외부 호출 전에 멱등 키를 커밋한다.
 * DuplicateKeyException은 호출자에게 전파한다.
 */
@Component
@RequiredArgsConstructor
public class ProcessedEventInserter {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(String eventId, String eventType) {
        LocalDateTime now = LocalDateTime.now(clock);
        jdbcTemplate
            .update(
                "INSERT INTO processed_events (event_id, event_type, processed_at, created_at) VALUES (?, ?, ?, ?)",
                eventId,
                eventType,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now)
            );
    }
}
