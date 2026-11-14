package com.backtoback.problem.global.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Spring Redis 접속 설정을 분산 잠금용 Redisson 클라이언트에 연결한다. */
@Configuration(proxyBeanMethods = false)
public class RedisConfiguration {
    /**
     * 공통 Redis 접속 설정으로 분산 잠금용 클라이언트를 생성한다.
     * 단일 Redis 서버를 사용하며 잠금 자동 연장의 기준 만료 시간은 30초다.
     *
     * @param properties Redis 호스트·포트·인증·연결 제한 설정
     * @return 애플리케이션 종료 시 정리되는 Redisson 클라이언트
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(DataRedisProperties properties) {
        Config config = new Config();
        String scheme = properties.getSsl().isEnabled() ? "rediss://" : "redis://";
        var server
            = config
                .useSingleServer()
                .setAddress(scheme + properties.getHost() + ":" + properties.getPort())
                .setDatabase(properties.getDatabase());
        if (StringUtils.hasText(properties.getUsername())) {
            config.setUsername(properties.getUsername());
        }
        if (StringUtils.hasText(properties.getPassword())) {
            config.setPassword(properties.getPassword());
        }
        // 미설정된 제한 시간은 Redisson 기본값을 유지한다.
        if (properties.getConnectTimeout() != null) {
            server.setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()));
        }
        if (properties.getTimeout() != null) {
            server.setTimeout(Math.toIntExact(properties.getTimeout().toMillis()));
        }
        // leaseTime 없이 잠금을 얻으면 Redisson이 작업 중 만료 시간을 자동 연장한다.
        config.setLockWatchdogTimeout(30_000);
        return Redisson.create(config);
    }
}
