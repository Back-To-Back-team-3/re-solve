package com.backtoback.problem.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.testfixture.problem.PersistenceProbe;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;

/** 서비스 JPA 감사 설정과 기반 Entity를 MySQL에 연결해 감사 시각·버전·동시 수정 거부를 확인한다. */
@ExtendWith(MockitoExtension.class)
class PersistenceConfigurationTest {
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-10-18T00:00:00.123456789Z"), ZoneOffset.UTC);

    private AnnotationConfigApplicationContext context;
    private EntityManagerFactory factory;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker가 없어 JPA 통합 테스트를 건너뜁니다.");
        MYSQL.start();
    }

    @AfterAll
    static void stopDatabase() {
        MYSQL.stop();
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/jpa/persistence-probe.sql")).execute(dataSource);
        context = new AnnotationConfigApplicationContext();
        context.registerBean("testClock", Clock.class, () -> clock, definition -> definition.setPrimary(true));
        context.registerBean("entityManagerFactory", LocalContainerEntityManagerFactoryBean.class, () -> {
            var bean = new LocalContainerEntityManagerFactoryBean();
            bean.setDataSource(dataSource);
            bean.setPackagesToScan(PersistenceProbe.class.getPackageName());
            bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return bean;
        });
        context.register(PersistenceConfiguration.class);
        context.refresh();
        factory = context.getBean(EntityManagerFactory.class);
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("저장·수정 시 고정 시각을 서울 시간과 마이크로초로 기록하고 버전을 증가시킨다")
    void auditsPersistAndUpdateWithVersion() {
        // given
        var createdTime = LocalDateTime.parse("2026-10-18T09:00:00.123456");
        var updatedTime = LocalDateTime.parse("2026-10-18T09:00:02.987654");
        try (var manager = factory.createEntityManager()) {
            // when
            manager.getTransaction().begin();
            manager.persist(PersistenceProbe.create(1L, "before"));
            manager.getTransaction().commit();
            manager.clear();
            var saved = manager.find(PersistenceProbe.class, 1L);

            // then
            assertThat(saved.getCreatedAt()).isEqualTo(createdTime);
            assertThat(saved.getUpdatedAt()).isEqualTo(createdTime);
            assertThat(saved.getVersion()).isZero();

            // when
            given(clock.instant()).willReturn(Instant.parse("2026-10-18T00:00:02.987654999Z"));
            manager.getTransaction().begin();
            saved.rename("after");
            manager.getTransaction().commit();
            manager.clear();
            var updated = manager.find(PersistenceProbe.class, 1L);

            // then
            assertThat(updated.getName()).isEqualTo("after");
            assertThat(updated.getCreatedAt()).isEqualTo(createdTime);
            assertThat(updated.getUpdatedAt()).isEqualTo(updatedTime);
            assertThat(updated.getVersion()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("같은 버전을 읽은 두 트랜잭션 중 늦게 수정한 요청을 거부하고 먼저 저장한 값을 유지한다")
    void rejectsUpdateWithStaleVersion() {
        // given
        try (var first = factory.createEntityManager(); var second = factory.createEntityManager()) {
            first.getTransaction().begin();
            first.persist(PersistenceProbe.create(1L, "before"));
            first.getTransaction().commit();
            first.clear();
            first.getTransaction().begin();
            second.getTransaction().begin();
            var winner = first.find(PersistenceProbe.class, 1L);
            var stale = second.find(PersistenceProbe.class, 1L);

            // when
            winner.rename("winner");
            first.getTransaction().commit();
            stale.rename("stale");

            // then
            try {
                assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
            } finally {
                if (second.getTransaction().isActive()) {
                    second.getTransaction().rollback();
                }
            }
            first.clear();
            var persisted = first.find(PersistenceProbe.class, 1L);
            assertThat(persisted.getName()).isEqualTo("winner");
            assertThat(persisted.getVersion()).isEqualTo(1L);
        }
    }
}
