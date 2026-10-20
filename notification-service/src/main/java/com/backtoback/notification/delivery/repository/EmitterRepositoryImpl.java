package com.backtoback.notification.delivery.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Repository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 사용자별 SseEmitter 세션을 메모리(ConcurrentHashMap)에서 스레드 안전하게 관리하는 저장소 구현체.
 */
@Repository
public class EmitterRepositoryImpl implements EmitterRepository {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> memberEmitterIds = new ConcurrentHashMap<>();
    private final Map<String, Long> emitterMemberIds = new ConcurrentHashMap<>();
    private final Map<String, Long> creationTimes = new ConcurrentHashMap<>();

    @Override
    public SseEmitter save(String emitterId, Long memberId, SseEmitter emitter) {
        emitters.put(emitterId, emitter);
        creationTimes.put(emitterId, System.currentTimeMillis());
        emitterMemberIds.put(emitterId, memberId);

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
            SseEmitter emitter = emitters.get(id);
            if (emitter != null) {
                result.add(emitter);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public Map<String, SseEmitter> findAll() {
        return Collections.unmodifiableMap(emitters);
    }

    @Override
    public Long getCreationTime(String emitterId) {
        return creationTimes.get(emitterId);
    }

    @Override
    public void deleteById(String emitterId) {
        emitters.remove(emitterId);
        creationTimes.remove(emitterId);
        Long memberId = emitterMemberIds.remove(emitterId);

        if (memberId != null) {
            memberEmitterIds.computeIfPresent(memberId, (key, ids) -> {
                ids.remove(emitterId);
                return ids.isEmpty() ? null : ids;
            });
        }
    }
}
