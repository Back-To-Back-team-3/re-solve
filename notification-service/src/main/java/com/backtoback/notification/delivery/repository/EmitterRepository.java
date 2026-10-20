package com.backtoback.notification.delivery.repository;

import java.util.List;
import java.util.Map;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 사용자별 실시간 SSE 세션(SseEmitter)을 보관하고 관리하는 저장소 인터페이스.
 */
public interface EmitterRepository {

    /**
     * Emitter 세션을 저장한다.
     *
     * @param emitterId Emitter 고유 식별자
     * @param memberId 회원 ID
     * @param emitter SSE 이미터 객체
     * @return 저장된 이미터
     */
    SseEmitter save(String emitterId, Long memberId, SseEmitter emitter);

    /**
     * 회원 ID에 등록된 모든 활성 이미터를 조회한다.
     *
     * @param memberId 회원 ID
     * @return 해당 회원의 이미터 목록
     */
    List<SseEmitter> findAllByMemberId(Long memberId);

    /**
     * 현재 등록된 모든 이미터를 식별자 맵으로 조회한다.
     *
     * @return 이미터 식별자와 이미터 맵
     */
    Map<String, SseEmitter> findAll();

    /**
     * 이미터 식별자에 해당하는 Emitter의 생성 시각(epoch millis)을 조회한다.
     *
     * @param emitterId Emitter 고유 식별자
     * @return 생성 시각(밀리초), 존재하지 않으면 null
     */
    Long getCreationTime(String emitterId);

    /**
     * 식별자에 해당하는 Emitter를 저장소에서 제거한다.
     *
     * @param emitterId Emitter 고유 식별자
     */
    void deleteById(String emitterId);
}
