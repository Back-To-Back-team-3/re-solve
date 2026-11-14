package com.backtoback.common.event.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.backtoback.common.event.dto.ReceivedEvent;

/**
 * 서비스가 등록한 핸들러를 이벤트 종류와 메시지 형식 버전의 조합으로 연결한다.
 * 모르는 이벤트 종류는 기록만 남기고, 아는 종류의 미지원 버전은 다른 핸들러로 오인하지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>이벤트 계약서 v1.0 / §1.6 스키마 변경·§5.1 소비자 구현 체크리스트: 버전별 연결과 모르는 이벤트 처리</li>
 * </ul>
 */
public class EventHandlerRegistry {
    private final Map<HandlerKey, EventHandler> handlers;
    private final Set<String> knownEventTypes;

    /**
     * 같은 종류·버전의 중복 등록을 시작 시점에 거절해 실행 순서에 따른 업무 분기를 방지한다.
     *
     * @param registeredHandlers 서비스가 명시적으로 등록한 업무 핸들러
     * @throws IllegalArgumentException 핸들러 키가 비어 있거나 중복일 때
     */
    public EventHandlerRegistry(List<EventHandler> registeredHandlers) {
        var indexedHandlers = new HashMap<HandlerKey, EventHandler>();
        var eventTypes = new HashSet<String>();
        for (var handler : registeredHandlers) {
            if (handler.eventType() == null || handler.eventType().isBlank() || handler.schemaVersion() == null
                || handler.schemaVersion().isBlank()) {
                throw new IllegalArgumentException("Event handler type and schemaVersion are required");
            }
            var key = new HandlerKey(handler.eventType(), handler.schemaVersion());
            if (indexedHandlers.putIfAbsent(key, handler) != null) {
                throw new IllegalArgumentException("More than one handler is registered for " + key);
            }
            eventTypes.add(handler.eventType());
        }
        handlers = Map.copyOf(indexedHandlers);
        knownEventTypes = Set.copyOf(eventTypes);
    }

    /**
     * 정확히 일치하는 버전만 실행하고 모르는 종류는 업무 반영 없이 정상 반환한다.
     * 호출자는 이 메서드보다 먼저 소비 기록을 같은 트랜잭션에 INSERT해야 한다.
     *
     * @param event 형식 검사와 중복 선점을 마친 이벤트
     * @throws IllegalArgumentException 등록된 이벤트 종류의 형식 버전을 지원하지 않을 때
     */
    public void dispatch(ReceivedEvent event) {
        var key = new HandlerKey(event.metadata().eventType(), event.metadata().schemaVersion());
        var handler = handlers.get(key);
        if (handler != null) {
            handler.handle(event);
        } else if (knownEventTypes.contains(key.eventType())) {
            throw new IllegalArgumentException("Unsupported schemaVersion for " + key.eventType());
        }
    }

    /**
     * 도메인 순서 값과 무관하게 이벤트 종류·메시지 형식 버전만 비교하는 등록 키다.
     *
     * @param eventType 공통 헤더의 이벤트 종류
     * @param schemaVersion 메시지 형식 버전
     */
    private record HandlerKey(String eventType, String schemaVersion) {
    }
}
