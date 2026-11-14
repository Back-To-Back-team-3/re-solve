package com.backtoback.member.auth.store;

import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis 통합 테스트용 연결. Docker가 있으면 Testcontainers로 Redis를 띄우고,
 * {@code TEST_REDIS_PORT}가 있으면 그 Redis(일회용 인스턴스만 지정할 것)의 15번 DB를 쓴다. 둘 다 없으면 테스트를 건너뛴다.
 */
final class RedisTestSupport {

    private static final String REDIS_IMAGE = "redis:7.4-alpine";
    private static final int REDIS_PORT = 6379;
    private static final int TEST_DATABASE = 15;

    private static GenericContainer<?> container;
    private static LettuceConnectionFactory connectionFactory;

    private RedisTestSupport() {}

    static synchronized StringRedisTemplate redisTemplate() {
        if (connectionFactory == null) {
            connectionFactory = new LettuceConnectionFactory(configuration());
            connectionFactory.afterPropertiesSet();
            connectionFactory.start();
        }
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    static void flush(StringRedisTemplate redisTemplate) {
        redisTemplate.execute(connection -> {
            connection.serverCommands().flushDb();
            return null;
        }, true);
    }

    private static RedisStandaloneConfiguration configuration() {
        String port = System.getenv("TEST_REDIS_PORT");
        if (port != null && !port.isBlank()) {
            String host = System.getenv().getOrDefault("TEST_REDIS_HOST", "localhost");
            RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, Integer.parseInt(port));
            configuration.setDatabase(TEST_DATABASE);
            return configuration;
        }

        Assumptions
            .assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker 또는 TEST_REDIS_PORT가 없어 Redis 통합 테스트를 건너뜁니다."
            );
        container = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(REDIS_PORT);
        container.start();
        return new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(REDIS_PORT));
    }
}
