package com.backtoback.common.event.store;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 서비스 스키마에서 보관기간이 지난 이벤트 기록만 배치 단위로 삭제한다.
 * 작업 예약과 잠금은 common-scheduling이 담당하며 감사 로그와 업무 테이블은 조회하거나 변경하지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>스케줄러 v1.0 / §0.2.2 OutboxCleanupScheduler·§0.2.3 ProcessedEventCleanupScheduler: 삭제 조건·1,000건 상한</li>
 * <li>서비스 공통 모듈 도입 / 이벤트·스케줄링 공통 구조: 이벤트 정리 저장 처리의 모듈 책임</li>
 * </ul>
 */
@RequiredArgsConstructor
public class JdbcEventCleanupStore {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 발행 완료 시각이 경계보다 이전인 PUBLISHED만 삭제하며 경계와 같은 시각은 보존한다.
     *
     * @param cutoff 발행 완료 기록의 보관 경계인 서울 시간
     * @return 이번 트랜잭션에서 삭제한 행 수, 최대 1,000건
     * @throws org.springframework.transaction.IllegalTransactionStateException 정리 트랜잭션이 없을 때
     * @throws org.springframework.dao.DataAccessException 삭제 또는 DB 제약 검사가 실패할 때
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deletePublishedBefore(LocalDateTime cutoff) {
        return jdbcTemplate.update("""
            DELETE FROM outbox_events
            WHERE status = 'PUBLISHED' AND published_at < ?
            ORDER BY id LIMIT 1000
            """, Timestamp.valueOf(cutoff));
    }

    /**
     * 완료 시각이 경계보다 이전인 소비 기록만 삭제하며 수신 시각은 보관기간 판단에 사용하지 않는다.
     *
     * @param cutoff 소비 완료 기록의 보관 경계인 서울 시간
     * @return 이번 트랜잭션에서 삭제한 행 수, 최대 1,000건
     * @throws org.springframework.transaction.IllegalTransactionStateException 정리 트랜잭션이 없을 때
     * @throws org.springframework.dao.DataAccessException 삭제 또는 DB 제약 검사가 실패할 때
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteProcessedBefore(LocalDateTime cutoff) {
        return jdbcTemplate.update("""
            DELETE FROM processed_events
            WHERE processed_at < ?
            ORDER BY id LIMIT 1000
            """, Timestamp.valueOf(cutoff));
    }
}
