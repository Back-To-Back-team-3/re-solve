package com.backtoback.common.event.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.event.dto.ReceivedEvent;
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.common.time.ClockConfiguration;

/**
 * 소비 기록 선점·업무 반영을 커밋한 뒤에만 서비스가 제공한 ACK를 실행한다.
 * 소비 기록 INSERT의 중복 충돌만 별도로 확인하며 업무 UNIQUE 오류는 재전달 대상으로 전파한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>이벤트 계약서 v1.0 / §1.4 소비 규칙·§5.1 소비자 구현 체크리스트: 원자 소비·커밋 후 ACK·MDC</li>
 * <li>공통 모듈 적용 가이드 / §2.4 common-event·common-scheduling — 이벤트와 스케줄링 설계: 실제 완료 ID 확인</li>
 * </ul>
 */
public class EventConsumer {
    private final EventMessageDecoder decoder;
    private final JdbcProcessedEventStore processedEventStore;
    private final EventHandlerRegistry handlerRegistry;
    private final Clock clock;
    private final TransactionTemplate consumeTransaction;
    private final TransactionTemplate completionQuery;

    /**
     * 서비스 업무 DB의 관리자로 독립 소비 트랜잭션과 중복 확인용 새 조회 경계를 구성한다.
     *
     * @param decoder 공통 헤더 검사와 SNS 봉투 해제기
     * @param processedEventStore 같은 업무 DB에 연결한 소비 기록 저장소
     * @param handlerRegistry 서비스가 등록한 종류·버전별 업무 처리기
     * @param clock 서비스 공통 Clock 또는 고정 테스트 Clock
     * @param transactionManager 서비스의 업무 트랜잭션 관리자
     */
    public EventConsumer(
        EventMessageDecoder decoder,
        JdbcProcessedEventStore processedEventStore,
        EventHandlerRegistry handlerRegistry,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        this.decoder = decoder;
        this.processedEventStore = processedEventStore;
        this.handlerRegistry = handlerRegistry;
        this.clock = clock;
        consumeTransaction = new TransactionTemplate(transactionManager);
        consumeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        completionQuery = new TransactionTemplate(transactionManager);
        completionQuery.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        completionQuery.setReadOnly(true);
    }

    /**
     * 공통 처리가 성공한 뒤 ACK를 호출하며 ACK 실패 시 완료 기록을 유지해 재수신을 멱등 처리한다.
     * 트랜잭션 템플릿으로 커밋 반환을 확인하므로 직접 생성해 호출해도 ACK가 커밋을 앞서지 않는다.
     *
     * @param message SQS에서 받은 Raw 이벤트 또는 SNS 봉투 본문
     * @param acknowledgement 완료까지 동기적으로 수행할 서비스 리스너의 수동 ACK 또는 메시지 삭제 동작
     * @throws IllegalArgumentException ACK가 없거나 수신 메타데이터·지원 버전이 유효하지 않을 때
     * @throws org.springframework.dao.DataAccessException DB·업무 반영 또는 실제 완료 조회가 실패할 때
     * @throws RuntimeException 업무 핸들러·JSON 해석·커밋·ACK가 실패할 때
     */
    public void consume(String message, Runnable acknowledgement) {
        if (acknowledgement == null) {
            throw new IllegalArgumentException("A manual acknowledgement action is required");
        }
        var event = decoder.decode(message);
        var previousContext = MDC.getCopyOfContextMap();
        try {
            MDC.put("eventId", event.metadata().eventId().toString());
            MDC.put("eventType", event.metadata().eventType());
            MDC.put("correlationId", event.metadata().correlationId());
            try {
                consumeTransaction.executeWithoutResult(status -> process(event));
            } catch (DuplicateProcessedEventException duplicate) {
                var completed
                    = completionQuery.execute(status -> processedEventStore.exists(event.metadata().eventId()));
                if (!Boolean.TRUE.equals(completed)) {
                    throw duplicate.originalFailure;
                }
            }
            acknowledgement.run();
        } finally {
            restoreContext(previousContext);
        }
    }

    /**
     * 중복 INSERT와 업무 처리의 예외를 분리해 일반 업무 UNIQUE 실패를 성공으로 오인하지 않는다.
     *
     * @param event 공통 형식 검사를 통과한 수신 이벤트
     */
    private void process(ReceivedEvent event) {
        var now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
        try {
            processedEventStore.insert(event.metadata(), now);
        } catch (DuplicateKeyException duplicate) {
            throw new DuplicateProcessedEventException(duplicate);
        }
        handlerRegistry.dispatch(event);
    }

    /**
     * 같은 리스너 스레드가 다음 메시지를 처리할 때 이전 이벤트의 추적 정보가 남지 않도록 복원한다.
     *
     * @param previousContext 수신 전 MDC 또는 MDC가 없었다면 null
     */
    private void restoreContext(Map<String, String> previousContext) {
        if (previousContext == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousContext);
        }
    }

    /**
     * 소비 기록 INSERT에서만 발생한 중복임을 롤백 이후까지 전달하는 내부 예외다.
     */
    private static class DuplicateProcessedEventException extends RuntimeException {
        private final DuplicateKeyException originalFailure;

        /**
         * 업무 처리 실패와 구분할 소비 기록 중복 오류를 보존한다.
         *
         * @param originalFailure 소비 기록 INSERT에서 발생한 중복 오류
         */
        private DuplicateProcessedEventException(DuplicateKeyException originalFailure) {
            super(originalFailure);
            this.originalFailure = originalFailure;
        }
    }
}
