package com.backtoback.common.event.store;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.dto.EventMetadata;

import lombok.RequiredArgsConstructor;

/**
 * 서비스 스키마의 소비 기록을 업무 반영과 같은 트랜잭션에 저장한다.
 * 해당 이벤트 ID의 커밋된 기록만 중복 성공 근거로 사용하며 테이블과 Flyway는 서비스가 관리한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §3.2 처리 완료 이벤트: 불변 기록·{@code event_id} 유일 제약</li>
 * <li>공통 모듈 적용 가이드 / §2.4 common-event·common-scheduling — 이벤트와 스케줄링 설계: 실제 소비 완료 확인</li>
 * </ul>
 */
@RequiredArgsConstructor
public class JdbcProcessedEventStore {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 업무 반영에 앞서 이벤트 ID를 선점한다. 같은 트랜잭션의 커밋 전에는 완료 기록으로 보이지 않는다.
     *
     * @param metadata 수신 이벤트의 검사된 공통 헤더
     * @param receivedAt 소비 트랜잭션의 서울 시간·마이크로초 기준 시각
     * @throws org.springframework.transaction.IllegalTransactionStateException 소비 트랜잭션이 없을 때
     * @throws org.springframework.dao.DuplicateKeyException 이벤트 ID의 유일 제약과 충돌할 때
     * @throws org.springframework.dao.DataAccessException DB 기록에 실패할 때
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(EventMetadata metadata, LocalDateTime receivedAt) {
        jdbcTemplate
            .update(
                """
                    INSERT INTO processed_events (event_id, event_type, processed_at, created_at)
                    VALUES (?, ?, ?, ?)
                    """,
                metadata.eventId().toString(),
                metadata.eventType(),
                Timestamp.valueOf(receivedAt),
                Timestamp.valueOf(receivedAt)
            );
    }

    /**
     * INSERT 중복 트랜잭션을 롤백한 뒤 새 조회 경계에서 같은 ID의 완료 기록을 확인한다.
     *
     * @param eventId 중복 수신했다고 보고된 이벤트 ID
     * @return 해당 ID의 커밋된 기록이 있으면 {@code true}
     * @throws org.springframework.transaction.IllegalTransactionStateException 완료 조회 트랜잭션이 없을 때
     * @throws org.springframework.dao.DataAccessException 완료 조회에 실패할 때
     */
    @Transactional(
        readOnly = true,
        propagation = Propagation.MANDATORY
    )
    public boolean exists(UUID eventId) {
        return Boolean.TRUE
            .equals(
                jdbcTemplate
                    .queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM processed_events WHERE event_id = ?)",
                        Boolean.class,
                        eventId.toString()
                    )
            );
    }
}
