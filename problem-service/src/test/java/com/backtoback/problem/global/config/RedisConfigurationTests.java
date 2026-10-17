package com.backtoback.problem.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.time.Duration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;

class RedisConfigurationTests {
    /**
     * 각 제한 시간은 설정값을 적용하고 미설정이면 Redisson 기본값을 유지하는지 검증한다.
     *
     * @param connectTimeoutConfigured 연결 제한 시간 설정 여부
     * @param timeoutConfigured 응답 제한 시간 설정 여부
     */
    @ParameterizedTest
    @CsvSource(
        {
            "false,false",
            "true,false",
            "false,true",
            "true,true"
        }
    )
    void appliesOnlyConfiguredTimeouts(boolean connectTimeoutConfigured, boolean timeoutConfigured) {
        DataRedisProperties properties = new DataRedisProperties();
        if (connectTimeoutConfigured) {
            properties.setConnectTimeout(Duration.ofMillis(1250));
        }
        if (timeoutConfigured) {
            properties.setTimeout(Duration.ofMillis(2750));
        }
        var defaults = new Config().useSingleServer();
        RedissonClient client = mock(RedissonClient.class);

        // 실제 Redis 연결 대신 생성기에 전달된 설정을 확인한다.
        try (var redisson = mockStatic(Redisson.class)) {
            redisson.when(() -> Redisson.create(any(Config.class))).thenReturn(client);
            assertThat(new RedisConfiguration().redissonClient(properties)).isSameAs(client);

            ArgumentCaptor<Config> config = ArgumentCaptor.forClass(Config.class);
            redisson.verify(() -> Redisson.create(config.capture()));
            var server = config.getValue().useSingleServer();
            assertThat(server.getConnectTimeout())
                .isEqualTo(connectTimeoutConfigured ? 1250 : defaults.getConnectTimeout());
            assertThat(server.getTimeout()).isEqualTo(timeoutConfigured ? 2750 : defaults.getTimeout());
        }
    }
}
