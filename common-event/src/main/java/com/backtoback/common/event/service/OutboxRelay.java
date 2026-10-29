package com.backtoback.common.event.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.client.AwsEventPublisher;
import com.backtoback.common.event.store.JdbcOutboxStore;
import com.backtoback.common.time.ClockConfiguration;

import lombok.RequiredArgsConstructor;

/**
 * 잠금으로 선점한 최대 100건을 SNS/SQS로 발행하고 상태·실패 횟수를 같은 트랜잭션에 기록한다.
 * 성공 후 업무 갱신 실패나 커밋 전 장애가 발생하면 저장 메시지의 같은 ID로 다시 발행한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>스케줄러 v1.0 / §0.2.1 OutboxRelay — 이벤트 발행: 선점 트랜잭션·주기·배치·재시도·잠금 중 호출 예외</li>
 * <li>이벤트 계약서 v1.0 / §1.3 발행 규칙: 최소 1회 전달과 재발행 식별자 유지</li>
 * </ul>
 */
@RequiredArgsConstructor
public class OutboxRelay {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcOutboxStore outboxStore;
    private final AwsEventPublisher publisher;
    private final List<OutboxPublishedCallback> callbacks;
    private final Clock clock;

    /**
     * 호출자의 업무 트랜잭션과 분리한 발행 트랜잭션에서 선점·발행·조건부 업무 갱신을 수행한다.
     * AWS 발행 실패는 해당 행의 재시도로 기록하고 나머지 행을 계속 처리한다.
     * 업무 콜백·DB 실패는 성공으로 삼지 않고 발행 트랜잭션을 롤백한다.
     *
     * @return 이번 선점 배치에서 발행을 시도한 행 수
     * @throws org.springframework.dao.DataAccessException 선점 또는 상태 저장이 실패할 때
     * @throws RuntimeException 서비스 업무 콜백이 실패해 발행 트랜잭션을 취소할 때
     */
    @Scheduled(
        fixedDelay = 1000,
        initialDelay = 5000
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int relayBatch() {
        var events = outboxStore.lockPendingBatch();
        for (var event : events) {
            try {
                publisher.publish(event);
            } catch (RuntimeException failure) {
                var failed = outboxStore.recordFailure(event, now());
                // payload와 외부 오류 메시지에는 코드·인증 정보가 포함될 수 있어 기록하지 않는다.
                LOG
                    .warn(
                        "Outbox publication failed: eventId={}, eventType={}, retryCount={}, status={}, failure={}",
                        event.metadata().eventId(),
                        event.metadata().eventType(),
                        event.retryCount() + 1,
                        failed ? "FAILED" : "PENDING",
                        failure.getClass().getSimpleName()
                    );
                continue;
            }
            outboxStore.markPublished(event.id(), now());
            for (var callback : callbacks) {
                callback.onPublished(event);
            }
        }
        return events.size();
    }

    /**
     * 실제 발행 응답을 받은 시점의 시간을 DB와 같은 서울 시간·마이크로초로 구한다.
     *
     * @return 상태 변경에 기록할 현재 시각
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
    }
}
