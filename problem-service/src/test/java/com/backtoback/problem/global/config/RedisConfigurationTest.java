package com.backtoback.problem.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;

/** Redisson 클라이언트에 설정된 연결·응답 제한 시간을 적용한다. */
class RedisConfigurationTest {
    @ParameterizedTest
    @DisplayName("각 제한 시간이 설정된 경우에만 Redisson 기본값을 덮어쓴다")
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
