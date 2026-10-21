package com.backtoback.common.jpa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * H2의 MySQL 호환 모드에서 공통 감사 설정과 상속 Entity의 저장·수정을 검증한다.
 * UTC Clock을 주입해 시간대 변환과 마이크로초 절삭을 실제 감사 이벤트에 연결한다.
 * MySQL 전체 문법이나 운영 DB 연결은 검증 범위에 포함하지 않는다.
 */
class JpaAuditingTest {
    /**
     * 저장 후 다시 읽은 생성·수정 시간을 비교하고 변경 감지 UPDATE 후 생성 시간이 유지되는지 확인한다.
     */
    @Test
    @DisplayName("저장·수정 시 주입한 시각을 서울 시간과 마이크로초로 기록하고 생성 시간을 유지한다")
    void auditsPersistAndUpdateWithInjectedClock() {
        // given
        try (
            var context = new AnnotationConfigApplicationContext(TestConfiguration.class);
            var entityManager = context.getBean(EntityManagerFactory.class).createEntityManager()
        ) {
            var clock = context.getBean(AdjustableClock.class);
            clock.current = Instant.parse("2026-10-17T23:59:59.123456789Z");
            var createdTime = LocalDateTime.parse("2026-10-18T08:59:59.123456");
            var updatedTime = LocalDateTime.parse("2026-10-18T09:00:01.987654");

            // when: 최초 저장을 커밋하고 DB에서 다시 조회한다.
            entityManager.getTransaction().begin();
            entityManager.persist(new AuditedEntity(1L, "before"));
            entityManager.getTransaction().commit();
            entityManager.clear();
            var saved = entityManager.find(AuditedEntity.class, 1L);

            // then
            assertThat(saved.getCreatedAt()).isEqualTo(createdTime);
            assertThat(saved.getUpdatedAt()).isEqualTo(createdTime);

            // when: 같은 Entity를 변경하고 다음 시각으로 커밋한 뒤 다시 조회한다.
            clock.current = Instant.parse("2026-10-18T00:00:01.987654999Z");
            entityManager.getTransaction().begin();
            saved.name = "after";
            entityManager.getTransaction().commit();
            entityManager.clear();
            var updated = entityManager.find(AuditedEntity.class, 1L);

            // then
            assertThat(updated.name).isEqualTo("after");
            assertThat(updated.getCreatedAt()).isEqualTo(createdTime);
            assertThat(updated.getUpdatedAt()).isEqualTo(updatedTime);
        }
    }

    /**
     * 외부 DB 없이 공통 감사 설정과 테스트용 Clock을 연결하는 최소 JPA 환경이다.
     */
    @Configuration(proxyBeanMethods = false)
    @Import(JpaAuditingConfiguration.class)
    static class TestConfiguration {
        /**
         * 공통 기본 Clock보다 우선 주입해 저장과 수정 사이의 시각을 제어한다.
         *
         * @return UTC 시간대의 테스트 Clock
         */
        @Bean
        @Primary
        AdjustableClock testClock() {
            return new AdjustableClock();
        }

        /**
         * 테스트 Entity만 사용하는 메모리 DB와 Hibernate를 구성하고 종료 시 스키마를 제거한다.
         *
         * @return Spring이 초기화하고 종료할 EntityManagerFactory 생성 Bean
         */
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory() {
            var dataSource
                = new DriverManagerDataSource("jdbc:h2:mem:common_auditing;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(JpaAuditingTest.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
            return factory;
        }
    }

    /**
     * 감사 이벤트 사이의 시각을 대기 없이 변경하는 테스트 전용 Clock이다.
     */
    static class AdjustableClock extends Clock {
        private Instant current = Instant.EPOCH;

        /**
         * 감사 시간 공급자가 Clock의 시간대를 그대로 사용하지 않는지 확인할 UTC를 반환한다.
         *
         * @return UTC 시간대
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * 현재 시점은 유지하면서 지정한 시간대의 고정 Clock을 생성한다.
         *
         * @param zone 변환할 시간대
         * @return 현재 테스트 시점의 고정 Clock
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        /**
         * 테스트에서 마지막으로 지정한 시점을 제공한다.
         *
         * @return 현재 테스트 시점
         */
        @Override
        public Instant instant() {
            return current;
        }
    }

    /**
     * 공통 시간 컬럼을 상속하고 일반 필드 변경으로 감사 UPDATE를 발생시키는 테스트 Entity다.
     */
    @Entity
    @Table(name = "auditing_test_entity")
    public static class AuditedEntity extends BaseTimeEntity {
        @Id
        private Long id;

        private String name;

        /**
         * JPA가 DB 조회 결과를 생성할 때 사용하는 기본 생성자다.
         */
        protected AuditedEntity() {}

        /**
         * 저장하지 않은 감사 대상 Entity를 생성한다.
         *
         * @param id 테스트에서 식별할 키
         * @param name 변경 감지에 사용할 값
         */
        private AuditedEntity(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
