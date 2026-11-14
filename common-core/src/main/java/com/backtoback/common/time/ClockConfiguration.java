package com.backtoback.common.time;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 서비스의 시간 계산에 주입할 서울 시간 기준 Clock을 제공한다.
 * 사용하는 서비스에서 컴포넌트 스캔 또는 명시적 import로 등록해야 한다.
 * 테스트에서는 고정 Clock을 우선 주입해 같은 시간 기준으로 동작을 검증할 수 있다.
 * <p>기준 문서</p>
 * <ul>
 * <li>스케줄러 v1.0 / §0.1 공통 규칙: Asia/Seoul 시간대와 Clock 주입에 의한 시간 비교</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {
    // 서비스와 DB가 사용하는 공통 시간대
    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 호출 시점의 시스템 시간을 서울 시간대로 조회하는 Clock을 생성한다.
     *
     * @return 서비스의 시간 계산에 사용할 Clock
     */
    @Bean
    public Clock clock() {
        return Clock.system(SERVICE_ZONE);
    }
}
