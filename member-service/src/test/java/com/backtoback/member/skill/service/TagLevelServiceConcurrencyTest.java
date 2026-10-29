package com.backtoback.member.skill.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.repository.MemberRepository;
import com.backtoback.member.skill.client.ProblemTagClient;
import com.backtoback.member.skill.repository.MemberLevelHistoryRepository;
import com.backtoback.member.skill.repository.MemberTagLevelRepository;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TagLevelServiceConcurrencyTest {

    private static final int REQUESTS = 5;

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("member_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("resolve.auth.github.client-id", () -> "client-id");
        registry.add("resolve.auth.github.client-secret", () -> "client-secret");
        registry.add("resolve.auth.jwt.secret", () -> "test-jwt-secret-key-must-be-at-least-32-bytes");
    }

    @MockitoBean
    private ProblemTagClient problemTagClient;

    @Autowired
    private TagLevelService tagLevelService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private MemberTagLevelRepository tagLevelRepository;

    @Autowired
    private MemberLevelHistoryRepository historyRepository;

    @Test
    @DisplayName("같은 태그에 동시에 진입해도 한 건만 저장되고 나머지는 LEVEL_ALREADY_ENTERED")
    void concurrentEntryKeepsOneRow() throws Exception {
        given(problemTagClient.exists(3L)).willReturn(true);
        Long memberId = memberRepository.saveAndFlush(Member.registerWithGitHub(2001L, "lee-dev", null, null)).getId();
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            tasks.add(() -> {
                start.await();
                return tagLevelService.enter(memberId, 3L);
            });
        }

        int succeeded = 0;
        int alreadyEntered = 0;
        ExecutorService executor = Executors.newFixedThreadPool(REQUESTS);
        try {
            List<Future<Object>> results = tasks.stream().map(executor::submit).toList();
            start.countDown();
            for (Future<Object> result : results) {
                try {
                    result.get();
                    succeeded++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause())
                        .isInstanceOf(BusinessException.class)
                        .extracting("errorCode")
                        .isEqualTo(MemberErrorCode.LEVEL_ALREADY_ENTERED);
                    alreadyEntered++;
                }
            }
        } finally {
            executor.shutdown();
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(alreadyEntered).isEqualTo(REQUESTS - 1);
        assertThat(tagLevelRepository.findAllByMemberIdOrderByTagIdAsc(memberId)).hasSize(1);
        assertThat(historyRepository.count()).isEqualTo(1);
    }
}
