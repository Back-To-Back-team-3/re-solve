package com.backtoback.notification.delivery.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 사용자별 SseEmitter 세션을 메모리에서 스레드 안전하게 관리하는 저장소 구현체.
 * 세션 등록과 삭제의 원자성을 보장하여 동시 종료 시의 좀비 엔트리 잔존을 방지한다.
 */
@Repository
public class EmitterRepositoryImpl implements EmitterRepository {

    private static final class EmitterEntry {
        private final Long memberId;
        private final SseEmitter emitter;
        private final long creationTime;

        private EmitterEntry(Long memberId, SseEmitter emitter, long creationTime) {
            this.memberId = memberId;
            this.emitter = emitter;
            this.creationTime = creationTime;
        }
    }

    private final Map<String, EmitterEntry> entries = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> memberEmitterIds = new ConcurrentHashMap<>();

    @Override
    public synchronized SseEmitter save(String emitterId, Long memberId, SseEmitter emitter) {
        entries.put(emitterId, new EmitterEntry(memberId, emitter, System.currentTimeMillis()));

        memberEmitterIds.compute(memberId, (key, ids) -> {
            if (ids == null) {
                ids = ConcurrentHashMap.newKeySet();
            }
            ids.add(emitterId);
            return ids;
        });

        return emitter;
    }

    @Override
    public List<SseEmitter> findAllByMemberId(Long memberId) {
        Set<String> ids = memberEmitterIds.get(memberId);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<SseEmitter> result = new ArrayList<>();
        for (String id : ids) {
            EmitterEntry entry = entries.get(id);
            if (entry != null) {
                result.add(entry.emitter);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Map<String, SseEmitter> findAll() {
        Map<String, SseEmitter> result = new HashMap<>();
        entries.forEach((id, entry) -> result.put(id, entry.emitter));
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Long getCreationTime(String emitterId) {
        EmitterEntry entry = entries.get(emitterId);
        return entry != null ? entry.creationTime : null;
    }

    @Override
    public synchronized void deleteById(String emitterId) {
        EmitterEntry removed = entries.remove(emitterId);
        if (removed != null) {
            memberEmitterIds.computeIfPresent(removed.memberId, (key, ids) -> {
                ids.remove(emitterId);
                return ids.isEmpty() ? null : ids;
            });
        }
    }
}
