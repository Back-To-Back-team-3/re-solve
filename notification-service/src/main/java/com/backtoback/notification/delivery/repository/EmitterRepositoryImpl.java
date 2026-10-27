package com.backtoback.notification.delivery.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;

/**
 * 사용자별 SSE 세션(SseSession)을 로컬 JVM 메모리에서 스레드 안전하게 관리하는 저장소 구현체.
 * 세션 등록과 삭제의 원자성을 보장하며, 워터마크(lastSentNotificationId)와 생성 시각을
 * SseSession 내부에 통합 보관하여 삭제 시 상태 누수를 방지한다.
 * <p>
 * [설계 한계 및 확장 고려사항]
 * 현재 v1 범위에서는 단일 인스턴스 인메모리 저장소로 동작한다.
 * notification-service를 다중 인스턴스로 수평 확장할 경우, 특정 인스턴스에서 소비한 SQS 이벤트가
 * 다른 인스턴스에 연결된 SSE 클라이언트로 전달되지 않을 수 있으므로 Redis Pub/Sub 등 브로드캐스트 계층 도입이 필요하다.
 * </p>
 */
@Repository
public class EmitterRepositoryImpl implements EmitterRepository {

    private final Map<String, SseSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> memberEmitterIds = new ConcurrentHashMap<>();

    @Override
    public synchronized SseSession save(SseSession session) {
        sessions.put(session.emitterId(), session);

        memberEmitterIds.compute(session.memberId(), (key, ids) -> {
            if (ids == null) {
                ids = ConcurrentHashMap.newKeySet();
            }
            ids.add(session.emitterId());
            return ids;
        });

        return session;
    }

    @Override
    public List<SseSession> findAllByMemberId(Long memberId) {
        Set<String> ids = memberEmitterIds.get(memberId);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<SseSession> result = new ArrayList<>();
        for (String id : ids) {
            SseSession session = sessions.get(id);
            if (session != null) {
                result.add(session);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<SseSession> findAll() {
        return List.copyOf(sessions.values());
    }

    @Override
    public synchronized void deleteById(String emitterId) {
        SseSession removed = sessions.remove(emitterId);
        if (removed != null) {
            memberEmitterIds.computeIfPresent(removed.memberId(), (key, ids) -> {
                ids.remove(emitterId);
                return ids.isEmpty() ? null : ids;
            });
        }
    }
}
