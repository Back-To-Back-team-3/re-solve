package com.backtoback.member.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.repository.MemberRepository;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MemberServiceConcurrencyTest {

    private static final int REQUESTS = 10;

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

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("같은 회원의 닉네임을 동시에 바꿔도 profileVersion이 요청 수만큼 빠짐없이 오른다")
    void concurrentNicknameUpdatesKeepEveryVersion() throws Exception {
        Long memberId = memberRepository.saveAndFlush(Member.registerWithGitHub(1001L, "kim-dev", null, null)).getId();
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Member>> tasks = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            String nickname = "kim-" + i;
            tasks.add(() -> {
                start.await();
                return memberService.updateNickname(memberId, nickname);
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(REQUESTS);
        try {
            List<Future<Member>> results = tasks.stream().map(executor::submit).toList();
            start.countDown();
            for (Future<Member> result : results) {
                result.get();
            }
        } finally {
            executor.shutdown();
        }

        assertThat(memberRepository.findById(memberId).orElseThrow().getProfileVersion()).isEqualTo(1L + REQUESTS);
    }
}
