package com.backtoback.common.event.service;

import com.backtoback.common.event.dto.OutboxEvent;

/**
 * 발행 성공 후 서비스의 조건부 업무 갱신을 Outbox 발행 트랜잭션에 연결하는 확장 지점이다.
 * 서비스가 이벤트 종류와 업무 상태를 검사하고 같은 DB에서 갱신해야 한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>공통 모듈 적용 가이드 / §2.4 common-event·common-scheduling — 이벤트와 스케줄링 설계: 발행 후 확장 지점</li>
 * </ul>
 */
@FunctionalInterface
public interface OutboxPublishedCallback {
    /**
     * 메시지 발행과 Outbox 상태 변경 뒤, 커밋 전에 서비스 업무 상태를 조건부로 갱신한다.
     * 별도 트랜잭션이나 원격 호출을 시작하지 않으며 실패하면 예외를 그대로 전파한다.
     *
     * @param event 발행에 성공한 저장 이벤트
     */
    void onPublished(OutboxEvent event);
}
