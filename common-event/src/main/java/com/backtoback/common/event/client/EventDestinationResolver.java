package com.backtoback.common.event.client;

import com.backtoback.common.event.dto.EventDestination;
import com.backtoback.common.event.dto.OutboxEvent;

/**
 * 서비스가 자기 이벤트의 SNS 토픽 또는 SQS 큐를 선택하는 확장 지점이다.
 * 공통 모듈에 도메인별 이벤트명이나 환경별 리소스 주소를 고정하지 않는다.
 */
@FunctionalInterface
public interface EventDestinationResolver {
    /**
     * 선점한 이벤트를 전달할 서비스 소유 리소스를 선택한다.
     *
     * @param event 저장된 헤더와 업무 대상 정보를 가진 발행 대상
     * @return 해당 이벤트를 발행할 Standard 리소스
     */
    EventDestination resolve(OutboxEvent event);
}
