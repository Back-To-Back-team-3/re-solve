package com.backtoback.notification.delivery.repository;

import java.util.List;

/**
 * 사용자별 실시간 SSE 세션(SseSession)을 보관하고 관리하는 저장소 인터페이스.
 */
public interface EmitterRepository {

    /**
     * SSE 세션을 저장한다.
     *
     * @param session 저장할 SSE 세션 객체
     * @return 저장된 SSE 세션
     */
    SseSession save(SseSession session);

    /**
     * 회원 ID에 등록된 모든 활성 SSE 세션을 조회한다.
     *
     * @param memberId 회원 ID
     * @return 해당 회원의 활성 SSE 세션 목록
     */
    List<SseSession> findAllByMemberId(Long memberId);

    /**
     * 현재 등록된 모든 활성 SSE 세션을 조회한다.
     *
     * @return 전체 SSE 세션 목록
     */
    List<SseSession> findAll();

    /**
     * 식별자에 해당하는 SSE 세션을 저장소에서 제거한다.
     *
     * @param emitterId Emitter 고유 식별자
     */
    void deleteById(String emitterId);
}
