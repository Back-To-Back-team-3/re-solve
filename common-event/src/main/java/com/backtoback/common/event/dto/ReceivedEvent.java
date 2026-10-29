package com.backtoback.common.event.dto;

import tools.jackson.databind.JsonNode;

/**
 * 공통 헤더를 검사한 뒤 서비스 업무 핸들러에 전달하는 이벤트다.
 * 업무 필드를 JSON 트리로 유지해 모르는 선택 필드가 추가되어도 공통 수신 과정에서 거절하지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>이벤트 계약서 v1.0 / §1.1 공통 헤더·§1.6 스키마 변경: 메타데이터와 업무 필드 분리·선택 필드 호환</li>
 * </ul>
 *
 * @param metadata 공통 형식 검사를 통과한 수신 메타데이터
 * @param payload 공통 헤더를 제외한 서비스 업무 필드
 */
public record ReceivedEvent(EventMetadata metadata, JsonNode payload) {
}
