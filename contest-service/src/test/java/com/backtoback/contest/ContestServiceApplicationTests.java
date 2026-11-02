package com.backtoback.contest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * contest-service의 실제 공통 설정과 Testcontainers 인프라 프로퍼티 연결을 검증한다.
 * 테스트는 Testcontainers의 MySQL·Redis를 사용하며 AWS 메시징 클라이언트는 비활성화한다.
 */
@SpringBootTest(
    classes = {
        ContestServiceApplication.class,
        ContestServiceApplicationTests.FixedClockConfiguration.class
    },
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers
class ContestServiceApplicationTests {
    private static final String REDIS_PASSWORD = "contest-test-password";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");

    @Container
    static final GenericContainer<?> REDIS
        = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    /**
     * Testcontainers에서 실행한 DB와 Redis의 접속 속성을 Spring 환경에 주입한다.
     *
     * @param registry 컨테이너 접속값을 등록할 테스트 속성 레지스트리
     */
    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    /**
     * 실제 애플리케이션 컨텍스트에 공통 처리기를 등록하고 감사 시간 공급자를 연결하는지 확인한다.
     */
    @Test
    @DisplayName("공통 처리기를 등록하고 JPA 감사 시간 공급자를 연결한다")
    void registersCommonBeansOnce() {
        // given: Testcontainers 기반 애플리케이션 컨텍스트가 초기화됐다.
        // when
        var provider = context.getBean("auditingDateTimeProvider", DateTimeProvider.class);
        var providedTime = provider.getNow();

        // then
        assertThat(context.getBeansOfType(com.backtoback.common.web.exception.GlobalExceptionHandler.class)).hasSize(1);
        assertThat(context.getBeansOfType(com.backtoback.common.web.logging.CorrelationIdFilter.class)).hasSize(1);
        assertThat(context.getBean("correlationIdCustomizer", RestClientCustomizer.class)).isNotNull();
        assertThat(context.getBean(Clock.class)).isSameAs(context.getBean("testClock"));
        assertThat(context.getBeansOfType(DateTimeProvider.class)).containsKey("auditingDateTimeProvider");
        assertThat(context.containsBean("jpaAuditingHandler")).isTrue();
        assertThat(providedTime).contains(LocalDateTime.parse("2026-10-18T08:59:59.123456"));
    }

    /**
     * Testcontainers가 제공한 연결값으로 contest 데이터베이스와 Redis 설정이 구성되는지 확인한다.
     */
    @Test
    @DisplayName("외부 주입한 데이터베이스와 Redis 연결값을 실제 서비스 설정에 적용한다")
    void bindsInfrastructureProperties() {
        // given: Testcontainers 연결값이 동적 프로퍼티로 주입됐다.
        // when
        var databaseName = jdbc.queryForObject("SELECT DATABASE()", String.class);
        // then
        assertThat(databaseName).isEqualTo("contest_db");
        try (var redisConnection = redisConnectionFactory.getConnection()) {
            assertThat(redisConnection.ping()).isEqualTo("PONG");
        }
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(MYSQL.getJdbcUrl());
        assertThat(environment.getProperty("spring.data.redis.port", Integer.class))
            .isEqualTo(REDIS.getMappedPort(6379));
    }

    /**
     * local 프로파일 파일을 실제 ConfigData 로더로 읽어 로컬 개발 속성을 확인한다.
     */
    @Test
    @DisplayName("local 설정 파일은 메시징을 끄고 Swagger를 켠다")
    void loadsLocalConfiguration() {
        // given
        var runner = new ApplicationContextRunner().withInitializer(context -> {
            ConfigurableEnvironment environment = (ConfigurableEnvironment)context.getEnvironment();
            environment
                .getPropertySources()
                .addFirst(
                    new MapPropertySource(
                        "test-config-location",
                        Map.of("spring.config.location", "classpath:/", "spring.profiles.active", "local")
                    )
                );
            new ConfigDataApplicationContextInitializer().initialize(context);
        });

        // when & then
        runner.run(context -> {
            assertThat(context.getEnvironment().getProperty("spring.cloud.aws.sns.enabled", Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty("spring.cloud.aws.sqs.enabled", Boolean.class)).isFalse();
            assertThat(context.getEnvironment().getProperty("springdoc.api-docs.enabled", Boolean.class)).isTrue();
            assertThat(context.getEnvironment().getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isTrue();
        });
    }

    /**
     * 공통 감사 공급자에 결정적인 시각을 주입하는 테스트 전용 설정이다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        /**
         * UTC 고정 시각을 반환해 공통 감사 공급자의 서울 변환과 정밀도를 검증한다.
         *
         * @return 감사 시각 검증용 Clock
         */
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-17T23:59:59.123456789Z"), ZoneOffset.UTC);
        }
    }
}
