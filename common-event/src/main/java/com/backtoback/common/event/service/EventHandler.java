package com.backtoback.common.event.service;

import com.backtoback.common.event.dto.ReceivedEvent;

/**
 * 서비스가 이벤트 종류·형식 버전별 업무 반영을 구현하는 확장 지점이다.
 * 순서 값·업무 상태·권한은 서비스에서 판단하고 외부 호출은 커밋 이후 별도 처리한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>이벤트 계약서 v1.0 / §1.4 소비 규칙·§1.5 순서 값 규칙·§1.6 스키마 변경: 업무 책임과 버전별 처리</li>
 * </ul>
 */
public interface EventHandler {
    /**
     * 핸들러가 처리할 이벤트 종류를 제공한다.
     *
     * @return 이벤트 공통 헤더의 종류 이름
     */
    String eventType();

    /**
     * 업무 순서 값과 구분해 처리할 메시지 형식 버전을 제공한다.
     *
     * @return 이 핸들러가 지원하는 형식 버전
     */
    String schemaVersion();

    /**
     * 소비 기록 INSERT 이후 같은 업무 트랜잭션에서 서비스 상태를 반영한다.
     * 새 트랜잭션을 만들거나 실패를 삼키지 않아야 하며 알려지지 않은 선택 필드는 무시한다.
     *
     * @param event 공통 메타데이터와 서비스 업무 필드
     * @throws RuntimeException 업무 반영에 실패해 소비 기록과 함께 롤백해야 할 때
     */
    void handle(ReceivedEvent event);
}
