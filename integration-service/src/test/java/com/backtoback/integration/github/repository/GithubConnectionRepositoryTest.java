package com.backtoback.integration.github.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.integration.github.domain.GithubConnection;
import com.backtoback.integration.github.domain.GithubConnectionStatus;
import com.backtoback.integration.github.service.TokenEncryptor;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class GithubConnectionRepositoryTest {

    private static final String TEST_SECRET_KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("integration_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("integration.crypto.secret-key", () -> TEST_SECRET_KEY_BASE64);
    }

    @Autowired
    private GithubConnectionRepository githubConnectionRepository;

    @Autowired
    private TokenEncryptor tokenEncryptor;

    @Test
    @DisplayName("암호화된 토큰을 가진 GitHub 연동 정보를 DB에 저장하고 조회 시 평문이 아닌 암호문이 저장되며 복호화가 가능하다")
    void saveAndFindConnectionWithEncryptedToken() {
        String rawToken = "ghp_secure_github_user_token_value_12345";
        byte[] encryptedToken = tokenEncryptor.encrypt(rawToken);

        // 암호문은 평문 바이트와 일치하지 않음 검증
        assertThat(encryptedToken).isNotEqualTo(rawToken.getBytes(StandardCharsets.UTF_8));

        GithubConnection connection
            = GithubConnection
                .builder()
                .memberId(100L)
                .installationId(200L)
                .encryptedToken(encryptedToken)
                .githubLogin("test-dev")
                .repositoryOwner("test-dev")
                .repositoryName("re-solve-repo")
                .commitPathRule("{난이도}/{문제번호}.java")
                .build();

        GithubConnection saved = githubConnectionRepository.save(connection);

        assertThat(saved.getId()).isNotNull();

        GithubConnection found = githubConnectionRepository.findByMemberId(100L).orElseThrow();
        assertThat(found.getInstallationId()).isEqualTo(200L);
        assertThat(found.getStatus()).isEqualTo(GithubConnectionStatus.CONNECTED);

        // DB에 저장된 토큰이 평문이 아님을 검증
        assertThat(found.getEncryptedToken()).isNotEqualTo(rawToken.getBytes(StandardCharsets.UTF_8));
        assertThat(found.getEncryptedToken()).isEqualTo(encryptedToken);

        // 복호화 시 원래 평문 토큰으로 복원됨을 검증
        String decrypted = tokenEncryptor.decrypt(found.getEncryptedToken());
        assertThat(decrypted).isEqualTo(rawToken);
    }
}
