package com.backtoback.problem.admin.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.problem.admin.store.AdminOutboxEventStore;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

import lombok.RequiredArgsConstructor;

/** 관리자 재처리의 상태 전환과 감사 기록을 같은 트랜잭션에 저장한다. (API §2.8.1) */
@Service
@RequiredArgsConstructor
public class AdminOutboxEventService {

    private static final int REPROCESS_WINDOW_DAYS = 7;

    private final AdminOutboxEventStore store;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 대상 잠금 후 유효 기간과 상태를 확인하고 전환 한 건당 감사 한 건을 기록한다.
     *
     * @param outboxEventId 재처리할 Outbox 행 식별자
     * @param actorId 인증한 관리자 식별자
     * @param reason 검증한 조치 사유
     * @param clientIp 요청의 네트워크 주소
     * @return 재처리한 Outbox 행 식별자
     * @throws ProblemException 대상 없음·발생 7일 초과이면 {@code OUTBOX_EVENT_NOT_FOUND},
     *     실패 상태가 아니거나 전환하지 못하면 {@code OUTBOX_EVENT_NOT_REPROCESSABLE}
     */
    @Transactional
    public long reprocess(long outboxEventId, long actorId, String reason, String clientIp) {
        var row
            = store
                .lockById(outboxEventId)
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.OUTBOX_EVENT_NOT_FOUND));
        var now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
        var cutoff = now.minusDays(REPROCESS_WINDOW_DAYS);
        if (row.occurredAt().isBefore(cutoff)) {
            throw new ProblemException(ProblemErrorCode.OUTBOX_EVENT_NOT_FOUND);
        }

        if (!"FAILED".equals(row.status())) {
            throw new ProblemException(ProblemErrorCode.OUTBOX_EVENT_NOT_REPROCESSABLE);
        }

        if (!store.resetFailedToPending(outboxEventId, now, cutoff)) {
            throw new ProblemException(ProblemErrorCode.OUTBOX_EVENT_NOT_REPROCESSABLE);
        }

        jdbc
            .update(
                """
                    INSERT INTO audit_logs(actor_id,action,target_type,target_id,before_state,after_state,
                        reason,client_ip,created_at)
                    VALUES (?,'OUTBOX_REPROCESS','OUTBOX_EVENT',?,
                        JSON_OBJECT('status',?,'retryCount',?,'publishedAt',CAST(? AS DATETIME(6))),
                        JSON_OBJECT('status','PENDING','retryCount',0,'publishedAt',NULL),?,?,?)
                    """,
                actorId,
                Long.toString(outboxEventId),
                row.status(),
                row.retryCount(),
                row.publishedAt(),
                reason,
                clientIp,
                now
            );

        return outboxEventId;
    }
}
