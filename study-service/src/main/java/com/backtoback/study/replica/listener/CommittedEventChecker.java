package com.backtoback.study.replica.listener;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.event.store.JdbcProcessedEventStore;

/**
 * 소비 트랜잭션이 롤백된 뒤 새 조회 트랜잭션에서 같은 이벤트의 커밋된 소비 기록을 확인한다
 * (event-contract.md §1.4 2, 공통 모듈 {@code EventConsumer}와 같은 판정).
 * 소비 기록 INSERT의 유일 제약 위반과 업무 테이블의 유일 제약 위반은 같은 예외로 나오므로, 커밋된 기록 여부로 구분한다.
 */
@Component
public class CommittedEventChecker {

    private final JdbcProcessedEventStore processedEventStore;
    private final TransactionTemplate completionQuery;

    public CommittedEventChecker(
        JdbcProcessedEventStore processedEventStore,
        PlatformTransactionManager transactionManager
    ) {
        this.processedEventStore = processedEventStore;
        completionQuery = new TransactionTemplate(transactionManager);
        completionQuery.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        completionQuery.setReadOnly(true);
    }

    /**
     * @return 해당 이벤트 ID의 소비 기록이 커밋되어 있으면 {@code true}
     */
    public boolean isCommitted(UUID eventId) {
        return Boolean.TRUE.equals(completionQuery.execute(status -> processedEventStore.exists(eventId)));
    }
}
