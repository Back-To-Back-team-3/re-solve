package com.backtoback.notification.delivery.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class EmitterRepositoryTest {

    private EmitterRepository emitterRepository;

    @BeforeEach
    void setUp() {
        emitterRepository = new EmitterRepositoryImpl();
    }

    @Test
    @DisplayName("SseSession을 저장하고 회원 ID로 정상 조회한다")
    void saveAndFindAllByMemberId() {
        // given
        Long memberId = 1L;
        String emitterId = "1_1000";
        SseEmitter emitter = new SseEmitter();
        SseSession session = SseSession.create(emitterId, memberId, emitter, 1000L, 50L);

        // when
        emitterRepository.save(session);

        // then
        List<SseSession> sessions = emitterRepository.findAllByMemberId(memberId);
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0)).isSameAs(session);
        assertThat(sessions.get(0).emitter()).isSameAs(emitter);
        assertThat(sessions.get(0).creationTime()).isEqualTo(1000L);
        assertThat(sessions.get(0).lastSentNotificationId().get()).isEqualTo(50L);
    }

    @Test
    @DisplayName("동일한 회원이 여러 세션(멀티 탭)을 열면 모든 SseSession이 정상 보관된다")
    void saveMultipleEmittersForSameMember() {
        // given
        Long memberId = 1L;
        SseSession session1 = SseSession.create("1_1001", memberId, new SseEmitter(), 1001L, 0L);
        SseSession session2 = SseSession.create("1_1002", memberId, new SseEmitter(), 1002L, 0L);

        // when
        emitterRepository.save(session1);
        emitterRepository.save(session2);

        // then
        List<SseSession> sessions = emitterRepository.findAllByMemberId(memberId);
        assertThat(sessions).hasSize(2).containsExactlyInAnyOrder(session1, session2);
    }

    @Test
    @DisplayName("식별자로 SseSession을 삭제하면 저장소와 회원 매핑에서 제거된다")
    void deleteById() {
        // given
        Long memberId = 1L;
        String emitterId1 = "1_1001";
        String emitterId2 = "1_1002";
        SseSession session1 = SseSession.create(emitterId1, memberId, new SseEmitter(), 1001L, 0L);
        SseSession session2 = SseSession.create(emitterId2, memberId, new SseEmitter(), 1002L, 0L);

        emitterRepository.save(session1);
        emitterRepository.save(session2);

        // when
        emitterRepository.deleteById(emitterId1);

        // then
        assertThat(emitterRepository.findAllByMemberId(memberId)).containsExactly(session2);
        assertThat(emitterRepository.findAll()).containsExactly(session2);

        // 두 번째 세션도 삭제 시 매핑이 완전히 비워진다
        emitterRepository.deleteById(emitterId2);
        assertThat(emitterRepository.findAllByMemberId(memberId)).isEmpty();
        assertThat(emitterRepository.findAll()).isEmpty();
    }
}
