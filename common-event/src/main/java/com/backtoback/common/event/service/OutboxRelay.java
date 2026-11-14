package com.backtoback.common.event.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.event.client.AwsEventPublisher;
import com.backtoback.common.event.dto.OutboxEvent;
import com.backtoback.common.event.store.JdbcOutboxStore;
import com.backtoback.common.time.ClockConfiguration;

/**
 * 회차당 최대 100건을 건별 트랜잭션으로 선점·발행하고 서비스의 조건부 업무 갱신과 함께 커밋한다.
 * 발행·후처리 실패는 해당 건만 롤백한 뒤 실패 횟수를 별도 기록하고 다른 이벤트를 계속 처리한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>스케줄러 v1.0 / §0.2.1 OutboxRelay — 이벤트 발행: 건별 트랜잭션·주기·배치·재시도·잠금 중 호출 예외</li>
 * <li>이벤트 계약서 v1.0 / §1.3 발행 규칙: 최소 1회 전달과 재발행 식별자 유지</li>
 * </ul>
 */
public class OutboxRelay {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_BATCH_SIZE = 100;

    private final JdbcOutboxStore outboxStore;
    private final AwsEventPublisher publisher;
    private final List<OutboxPublishedCallback> callbacks;
    private final Clock clock;
    private final TransactionTemplate transaction;

    /**
     * 서비스의 업무 갱신과 같은 트랜잭션 관리자로 건별 발행과 롤백 후 실패 기록을 분리한다.
     *
     * @param outboxStore 서비스 스키마에 연결한 Outbox 저장소
     * @param publisher 저장 메시지를 전송할 발행기
     * @param callbacks 발행 성공 후 같은 트랜잭션에서 실행할 업무 콜백
     * @param clock 상태 변경 시각을 제공할 서비스 Clock
     * @param transactionManager 서비스 업무 DB의 트랜잭션 관리자
     */
    public OutboxRelay(
        JdbcOutboxStore outboxStore,
        AwsEventPublisher publisher,
        List<OutboxPublishedCallback> callbacks,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        this.outboxStore = outboxStore;
        this.publisher = publisher;
        this.callbacks = List.copyOf(callbacks);
        this.clock = clock;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 실패한 ID도 이번 회차에서는 다시 고르지 않아 한 건의 반복 실패가 뒤의 이벤트를 막지 않는다.
     * 진행 위치는 회차 안에서만 사용하고 다음 회차·재기동 시에는 DB 처음부터 다시 선정한다.
     *
     * @return 이번 회차에 선점해 발행을 시도한 행 수
     * @throws RuntimeException 선점 조회 또는 롤백 후 실패 기록을 완료할 수 없을 때
     */
    @Scheduled(
        fixedDelay = 1000,
        initialDelay = 5000
    )
    public int relayBatch() {
        var afterId = 0L;
        var attempted = 0;
        while (attempted < MAX_BATCH_SIZE) {
            var event = relayNext(afterId);
            if (event == null) {
                break;
            }
            afterId = event.id();
            attempted++;
        }
        return attempted;
    }

    /**
     * 한 건의 발행·후처리·커밋을 격리하고 실패하면 해당 트랜잭션이 끝난 뒤 재시도를 기록한다.
     * 선점 전 조회 실패는 이벤트 실패로 세지 않고 회차를 중단하며 앞선 커밋은 보존한다.
     *
     * @param afterId 이번 회차에서 마지막으로 시도한 Outbox ID
     * @return 선점해 시도한 이벤트, 잠글 대상이 없으면 null
     * @throws RuntimeException 선점 조회 또는 실패 기록이 실패할 때
     */
    private OutboxEvent relayNext(long afterId) {
        var attempt = new RelayAttempt();
        try {
            transaction.executeWithoutResult(status -> {
                attempt.event = outboxStore.lockNextPending(afterId).orElse(null);
                if (attempt.event == null) {
                    return;
                }
                publisher.publish(attempt.event);
                outboxStore.markPublished(attempt.event.id(), now());
                for (var callback : callbacks) {
                    callback.onPublished(attempt.event);
                }
            });
        } catch (RuntimeException failure) {
            if (attempt.event == null) {
                throw failure;
            }
            recordFailureAfterRollback(attempt.event, failure);
        }
        return attempt.event;
    }

    /**
     * 다른 Relay의 최신 커밋을 확인한 뒤 대기 중인 행의 실패 횟수만 별도 트랜잭션으로 증가시킨다.
     * 실패 기록도 불가능하면 원인 예외를 보존해 회차를 중단하고 다음 회차가 DB에서 재선정한다.
     *
     * @param event 실패한 발행 시도의 이벤트
     * @param failure 발행·후처리·커밋 중 발생한 원인
     * @throws RuntimeException 실패 횟수 기록 트랜잭션이 실패할 때
     */
    private void recordFailureAfterRollback(OutboxEvent event, RuntimeException failure) {
        Integer retryCount;
        try {
            retryCount = transaction.execute(status -> {
                var pending = outboxStore.lockPendingForFailure(event.id());
                if (pending.isEmpty()) {
                    return null;
                }
                var current = pending.orElseThrow();
                outboxStore.recordFailure(current, now());
                return current.retryCount() + 1;
            });
        } catch (RuntimeException recordingFailure) {
            recordingFailure.addSuppressed(failure);
            throw recordingFailure;
        }
        if (retryCount != null) {
            // payload와 외부 오류 메시지에는 코드·인증 정보가 포함될 수 있어 기록하지 않는다.
            LOG
                .warn(
                    "Outbox publication failed: eventId={}, eventType={}, retryCount={}, status={}, failure={}",
                    event.metadata().eventId(),
                    event.metadata().eventType(),
                    retryCount,
                    retryCount > 5 ? "FAILED" : "PENDING",
                    failure.getClass().getSimpleName()
                );
        }
    }

    /**
     * 실제 발행·실패 처리 시각을 DB와 같은 서울 시간·마이크로초로 구한다.
     *
     * @return 상태 변경에 기록할 현재 시각
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 커밋 단계의 예외에서도 실패한 이벤트를 확인할 수 있도록 현재 건의 선점 결과만 보관한다.
     */
    private static final class RelayAttempt {
        // 선점할 행이 없거나 조회 자체가 실패했으면 null로 남는다.
        private OutboxEvent event;
    }
}
