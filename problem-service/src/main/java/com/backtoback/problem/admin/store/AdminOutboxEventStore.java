package com.backtoback.problem.admin.store;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** 자기 서비스 Outbox의 운영 재처리를 Relay와 같은 행 잠금으로 직렬화한다. (API §2.8.1) */
@Component
@RequiredArgsConstructor
public class AdminOutboxEventStore {
    private final JdbcTemplate jdbc;

    /**
     * 운영 트랜잭션에서 대상 행을 잠그고 감사에 필요한 기존 값을 조회한다.
     *
     * @param id Outbox 행 식별자
     * @return 잠금 획득 후 상태 또는 대상 없음
     * @throws org.springframework.transaction.IllegalTransactionStateException 호출자의 트랜잭션이 없는 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OutboxEventRow> lockById(long id) {
        return jdbc
            .query(
                """
                    SELECT id,status,retry_count,occurred_at,published_at FROM outbox_events WHERE id=? FOR UPDATE
                    """,
                (row, index) -> new OutboxEventRow(
                    row.getLong("id"),
                    row.getString("status"),
                    row.getInt("retry_count"),
                    row.getObject("occurred_at", LocalDateTime.class),
                    row.getObject("published_at", LocalDateTime.class)
                ),
                id
            )
            .stream()
            .findFirst();
    }

    /**
     * 잠근 실패 행이 유효 기간 안에 있을 때만 기존 이벤트를 다시 발행 대기로 전환한다.
     *
     * @param id 잠근 Outbox 행 식별자
     * @param now 잠금 획득 후 기준 시각
     * @param cutoff 재처리 가능한 발생 시각의 하한
     * @return 정확히 한 행을 전환했으면 {@code true}
     * @throws org.springframework.transaction.IllegalTransactionStateException 호출자의 트랜잭션이 없는 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean resetFailedToPending(long id, LocalDateTime now, LocalDateTime cutoff) {
        return jdbc.update("""
            UPDATE outbox_events SET status='PENDING',retry_count=0,published_at=NULL,updated_at=?
            WHERE id=? AND status='FAILED' AND occurred_at>=?
            """, now, id, cutoff) == 1;
    }

    /** 잠금 시점의 상태와 변경 전 감사 값. */
    public record OutboxEventRow(
        long id,
        String status,
        int retryCount,
        LocalDateTime occurredAt,
        LocalDateTime publishedAt
    ) {
    }
}
