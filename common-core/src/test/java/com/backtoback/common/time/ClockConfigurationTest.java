package com.backtoback.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 서비스에서 가져온 공통 설정이 서울 시간의 Clock을 등록하는지 검증한다.
 */
class ClockConfigurationTest {
    /**
     * 서버의 기본 시간대와 관계없이 사용할 Clock Bean의 시간대를 확인한다.
     */
    @Test
    @DisplayName("공통 Clock 설정은 서울 시간대의 Bean을 제공한다")
    void registersSeoulClock() {
        // given
        try (var context = new AnnotationConfigApplicationContext(ClockConfiguration.class)) {
            // when
            Clock clock = context.getBean(Clock.class);

            // then
            assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        }
    }
}
