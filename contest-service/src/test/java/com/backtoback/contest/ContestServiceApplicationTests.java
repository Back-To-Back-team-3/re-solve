package com.backtoback.contest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.contest.exam.repository.ExamRepository;

import jakarta.persistence.EntityManagerFactory;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * 실제 애플리케이션 설정으로 Flyway·JPA·AWS 클라이언트가 함께 초기화되는지 확인한다.
 * 테스트 전용 MySQL과 LocalStack을 사용하므로 개인 DB나 실제 AWS 계정에 연결하지 않는다.
 * 개발 Stub 프로필을 활성화하지 않아 DB 자동 설정과 스키마 검증을 유지한다.
 * SNS 발행·SQS 소비 동작은 이 구동 테스트의 검증 범위에 포함하지 않는다.
 */
@SpringBootTest
@Testcontainers
class ContestServiceApplicationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.36").withDatabaseName("contest_context_test");

    @Container
    static final LocalStackContainer AWS
        = new LocalStackContainer("localstack/localstack:4.4.0").withServices("sns", "sqs");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 컨테이너가 할당한 DB 주소와 AWS 대역의 접속 정보를 Spring 자동 설정에 전달한다.
     * AWS 자격 증명과 리전을 명시해 PC의 자격 증명 파일·인스턴스 메타데이터에 의존하지 않는다.
     *
     * @param registry 테스트 컨텍스트에 적용할 동적 설정
     */
    @DynamicPropertySource
    static void configureDependencyProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.cloud.aws.region.static", AWS::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", AWS::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", AWS::getSecretKey);
        registry.add("spring.cloud.aws.sns.endpoint", () -> AWS.getEndpoint().toString());
        registry.add("spring.cloud.aws.sqs.endpoint", () -> AWS.getEndpoint().toString());
    }

    /**
     * 전체 컨텍스트의 초기화 이후 실제 DB 접속과 JPA·메시징 클라이언트 등록을 확인한다.
     * Flyway 실행이나 Hibernate 스키마 검증이 실패하면 테스트 본문 실행 전에 실패한다.
     */
    @Test
    @DisplayName("테스트 전용 DB와 AWS 환경에서 전체 애플리케이션이 초기화된다")
    void contextLoads() {
        // given
        EntityManagerFactory entityManagerFactory = applicationContext.getBean(EntityManagerFactory.class);

        // when
        Integer databaseResult = jdbcTemplate.queryForObject("SELECT 1", Integer.class);

        // then
        assertThat(databaseResult).isEqualTo(1);
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(applicationContext.getBean(ExamRepository.class)).isNotNull();
        assertThat(applicationContext.getBean(SnsClient.class)).isNotNull();
        assertThat(applicationContext.getBean(SqsAsyncClient.class)).isNotNull();
    }

}
