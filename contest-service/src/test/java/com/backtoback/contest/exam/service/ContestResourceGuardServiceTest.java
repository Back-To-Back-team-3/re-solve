package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.contest.exam.repository.ContestResourceGuardRepository;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.Resource;
import com.backtoback.contest.global.error.ContestException;

/** MySQL에서 가드의 최초 생성과 잠금 및 신규 편입 차단을 검증한다. */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ContestResourceGuardServiceTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /** 테스트별 트랜잭션이 같은 MySQL과 Redis를 사용하도록 연결한다. */
    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private ContestResourceGuardRepository guards;
    @Autowired
    private ContestResourceGuardService enrollment;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcTemplate jdbc;

    /** 자원 순서와 중복 제거 및 트랜잭션 요구 조건을 확인한다. */
    @Test
    @DisplayName("가드를 자원 종류와 ID 순으로 잠그고 중복 자원은 한 번만 확보한다")
    void ordersAndDeduplicatesResources() {
        var member = new Resource("MEMBER", 99101);
        var problem = new Resource("PROBLEM", 99102);
        var other = new Resource("PROBLEM", 99103);
        assertThatThrownBy(() -> guards.lockAll(List.of(member))).isInstanceOf(IllegalTransactionStateException.class);
        transactions.executeWithoutResult(status -> {
            var result = enrollment.lockForEnrollment(List.of(other, problem, member, problem));
            assertThat(result)
                .extracting(ContestResourceGuardRepository.LockedGuard::resource)
                .containsExactly(member, problem, other);
            assertThat(result).extracting(ContestResourceGuardRepository.LockedGuard::state).containsOnly("OPEN");
            status.setRollbackOnly();
        });
        assertThat(count(99101)).isZero();
    }

    /** 이미 준비되었거나 차단된 자원은 신규 편입을 거절한다. */
    @Test
    @DisplayName("PREPARED와 BLOCKED 가드는 신규 편입을 거절한다")
    void blocksNonOpenResources() {
        for (String state : List.of("PREPARED", "BLOCKED")) {
            transactions.executeWithoutResult(status -> {
                guards.lockAll(List.of(new Resource("PROBLEM", 99104)));
                jdbc
                    .update(
                        "UPDATE contest_resource_guards SET state = ? WHERE resource_type = 'PROBLEM' "
                            + "AND resource_id = 99104",
                        state
                    );
                assertThatThrownBy(() -> enrollment.lockForEnrollment(List.of(new Resource("PROBLEM", 99104))))
                    .isInstanceOf(ContestException.class);
                status.setRollbackOnly();
            });
        }
    }

    /** 최초 생성 경합에서 뒤따르는 트랜잭션이 선행 커밋의 상태를 확인한다. */
    @Test
    @DisplayName("동시 최초 확보는 한 행으로 직렬화되고 뒤따르는 편입은 준비 상태를 확인한다")
    void concurrentFirstAcquisitionObservesPreparedState() throws Exception {
        var resource = new Resource("PROBLEM", 99105);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var preparing = executor.submit(() -> transactions.executeWithoutResult(status -> {
                guards.lockAll(List.of(resource));
                jdbc
                    .update(
                        "UPDATE contest_resource_guards SET state = 'PREPARED' "
                            + "WHERE resource_type = 'PROBLEM' AND resource_id = 99105"
                    );
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var joining = executor.submit(() -> {
                entered.countDown();
                return transactions.execute(status -> enrollment.lockForEnrollment(List.of(resource)));
            });
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> joining.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            preparing.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> joining.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ContestException.class);
            assertThat(count(99105)).isEqualTo(1);
        } finally {
            release.countDown();
            jdbc.update("DELETE FROM contest_resource_guards WHERE resource_type = 'PROBLEM' AND resource_id = 99105");
        }
    }

    /** 가드 행 수를 조회한다. */
    private int count(long id) {
        return jdbc
            .queryForObject("SELECT COUNT(*) FROM contest_resource_guards WHERE resource_id = ?", Integer.class, id);
    }

    /** 대기 실패를 트랜잭션 실패로 전달한다. */
    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
