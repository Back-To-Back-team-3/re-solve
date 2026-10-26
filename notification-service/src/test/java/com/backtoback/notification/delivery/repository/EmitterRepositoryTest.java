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
    @DisplayName("Emitter를 저장하고 회원 ID로 정상 조회한다")
    void saveAndFindAllByMemberId() {
        // given
        Long memberId = 1L;
        String emitterId = "1_1000";
        SseEmitter emitter = new SseEmitter();

        // when
        emitterRepository.save(emitterId, memberId, emitter);

        // then
        List<SseEmitter> emitters = emitterRepository.findAllByMemberId(memberId);
        assertThat(emitters).hasSize(1);
        assertThat(emitters.get(0)).isSameAs(emitter);
        assertThat(emitterRepository.getCreationTime(emitterId)).isNotNull();
    }

    @Test
    @DisplayName("동일한 회원이 여러 세션(멀티 탭)을 열면 모든 Emitter가 정상 보관된다")
    void saveMultipleEmittersForSameMember() {
        // given
        Long memberId = 1L;
        String emitterId1 = "1_1001";
        String emitterId2 = "1_1002";
        SseEmitter emitter1 = new SseEmitter();
        SseEmitter emitter2 = new SseEmitter();

        // when
        emitterRepository.save(emitterId1, memberId, emitter1);
        emitterRepository.save(emitterId2, memberId, emitter2);

        // then
        List<SseEmitter> emitters = emitterRepository.findAllByMemberId(memberId);
        assertThat(emitters).hasSize(2).containsExactlyInAnyOrder(emitter1, emitter2);
    }

    @Test
    @DisplayName("식별자로 Emitter를 삭제하면 저장소와 회원 매핑에서 제거된다")
    void deleteById() {
        // given
        Long memberId = 1L;
        String emitterId1 = "1_1001";
        String emitterId2 = "1_1002";
        SseEmitter emitter1 = new SseEmitter();
        SseEmitter emitter2 = new SseEmitter();

        emitterRepository.save(emitterId1, memberId, emitter1);
        emitterRepository.save(emitterId2, memberId, emitter2);

        // when
        emitterRepository.deleteById(emitterId1);

        // then
        assertThat(emitterRepository.findAllByMemberId(memberId)).containsExactly(emitter2);
        assertThat(emitterRepository.getCreationTime(emitterId1)).isNull();
        assertThat(emitterRepository.getCreationTime(emitterId2)).isNotNull();

        // 두 번째 emitter도 삭제 시 매핑이 완전히 비워진다
        emitterRepository.deleteById(emitterId2);
        assertThat(emitterRepository.findAllByMemberId(memberId)).isEmpty();
    }
}
