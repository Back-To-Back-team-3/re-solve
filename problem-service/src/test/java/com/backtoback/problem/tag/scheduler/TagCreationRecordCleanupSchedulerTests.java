package com.backtoback.problem.tag.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.problem.global.config.SchedulingConfig;
import com.backtoback.problem.tag.service.TagCreationRecordService;

@Testcontainers
class TagCreationRecordCleanupSchedulerTests {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");

    /**
     * 두 애플리케이션 인스턴스가 공유할 DB에 실제 마이그레이션을 적용한다.
     */
    @BeforeAll
    static void migrateDatabase() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
    }

    /**
     * 같은 DB를 사용하는 다른 인스턴스의 중복 실행을 건너뛰고 잠금 해제 후 실행하는지 검증한다.
     *
     * @throws Exception 동시 실행 대기 또는 결과 조회에 실패한 경우
     */
    @Test
    @DisplayName("한 인스턴스가 정리 중이면 다른 인스턴스는 건너뛰고 해제 후 실행한다")
    void skipsConcurrentExecutionAndRunsAfterUnlock() throws Exception {
        var firstService = mock(TagCreationRecordService.class);
        var secondService = mock(TagCreationRecordService.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(firstService.deleteExpired()).thenAnswer(invocation -> {
            started.countDown();
            if (!finish.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("첫 정리 작업의 종료 신호를 받지 못했습니다.");
            }
            return 0;
        });
        try (
            var first = createContext(firstService, true); var second = createContext(secondService, true);
            var executor = Executors.newVirtualThreadPerTaskExecutor()
        ) {
            var running
                = executor.submit(() -> first.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords());
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                second.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords();
                verify(secondService, never()).deleteExpired();
            } finally {
                finish.countDown();
            }
            running.get(5, TimeUnit.SECONDS);
            second.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords();
            verify(firstService).deleteExpired();
            verify(secondService).deleteExpired();
        }
    }

    /**
     * 정리 중 예외가 발생해도 잠금을 해제하여 다른 인스턴스가 다음 실행을 수행하는지 검증한다.
     */
    @Test
    @DisplayName("정리 작업이 실패해도 ShedLock을 해제한다")
    void releasesLockAfterFailure() {
        var firstService = mock(TagCreationRecordService.class);
        var secondService = mock(TagCreationRecordService.class);
        when(firstService.deleteExpired()).thenThrow(new IllegalStateException("정리 실패"));
        try (var first = createContext(firstService, true); var second = createContext(secondService, true)) {
            assertThatThrownBy(() -> first.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("정리 실패");
            second.getBean(TagCreationRecordCleanupScheduler.class).deleteExpiredRecords();
            verify(secondService).deleteExpired();
        }
    }

    /**
     * 정리 비활성화 설정이면 스케줄러를 등록하지 않는지 검증한다.
     */
    @Test
    @DisplayName("만료 정리를 비활성화하면 스케줄러 빈을 등록하지 않는다")
    void doesNotRegisterDisabledScheduler() {
        var service = mock(TagCreationRecordService.class);
        try (var context = createContext(service, false)) {
            assertThat(context.getBeansOfType(TagCreationRecordCleanupScheduler.class)).isEmpty();
            verify(service, never()).deleteExpired();
        }
    }

    /**
     * 실제 잠금 설정과 독립된 JDBC 연결을 사용하는 스케줄러 인스턴스를 생성한다.
     *
     * @param service 실행 여부를 검증할 정리 서비스
     * @param enabled 정리 스케줄러 활성화 여부
     * @return 호출자가 종료해야 하는 애플리케이션 컨텍스트
     */
    private AnnotationConfigApplicationContext createContext(TagCreationRecordService service, boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        if (!enabled) {
            context
                .getEnvironment()
                .getPropertySources()
                .addFirst(
                    new MapPropertySource("cleanup-test", Map.of("problem.tag-idempotency.cleanup.enabled", "false"))
                );
        }
        context
            .registerBean(
                JdbcTemplate.class,
                () -> new JdbcTemplate(
                    new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                )
            );
        context.registerBean(TagCreationRecordService.class, () -> service);
        context.register(SchedulingConfig.class, TagCreationRecordCleanupScheduler.class);
        context.refresh();
        return context;
    }
}
