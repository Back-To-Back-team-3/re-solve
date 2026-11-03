package com.backtoback.member.admin.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberRole;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.repository.MemberRepository;

/**
 * 검색 쿼리(LIKE 이스케이프·조건·정렬)는 MySQL 문법에 의존하므로 실제 MySQL로 검증한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AdminMemberServiceTest {

    private static final PageRequest NEWEST_FIRST
        = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));

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
    private AdminMemberService adminMemberService;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("회원 목록: 닉네임·로그인명 부분 일치와 상태·권한 조건으로 거르고, %·_는 문자 그대로 찾으며 최신 가입순으로 준다")
    void searchesWithFiltersAndLiteralWildcards() {
        Member kim = save(3001L, "kim-dev", MemberStatus.ACTIVE, MemberRole.USER);
        Member lee = save(3002L, "lee_algo", MemberStatus.SUSPENDED, MemberRole.USER);
        Member park = save(3003L, "park-100%", MemberStatus.ACTIVE, MemberRole.ADMIN);
        ReflectionTestUtils.setField(lee, "nickname", "kim_friend");
        memberRepository.saveAndFlush(lee);

        assertThat(ids(adminMemberService.searchMembers("kim", null, null, NEWEST_FIRST).getContent()))
            .containsExactly(lee.getId(), kim.getId());
        assertThat(ids(adminMemberService.searchMembers("kim", MemberStatus.ACTIVE, null, NEWEST_FIRST).getContent()))
            .containsExactly(kim.getId());
        assertThat(ids(adminMemberService.searchMembers(null, null, MemberRole.ADMIN, NEWEST_FIRST).getContent()))
            .contains(park.getId())
            .doesNotContain(kim.getId(), lee.getId());
        // 두 조건을 함께 주면 교집합만 남는다(한쪽 조건을 무시하면 kim 또는 lee가 섞인다).
        assertThat(
            ids(
                adminMemberService.searchMembers(null, MemberStatus.ACTIVE, MemberRole.ADMIN, NEWEST_FIRST).getContent()
            )
        ).contains(park.getId()).doesNotContain(kim.getId(), lee.getId());
        assertThat(
            ids(
                adminMemberService
                    .searchMembers("kim", MemberStatus.SUSPENDED, MemberRole.USER, NEWEST_FIRST)
                    .getContent()
            )
        ).containsExactly(lee.getId());
        assertThat(ids(adminMemberService.searchMembers("100%", null, null, NEWEST_FIRST).getContent()))
            .containsExactly(park.getId());
        assertThat(ids(adminMemberService.searchMembers("m_f", null, null, NEWEST_FIRST).getContent()))
            .containsExactly(lee.getId());
        assertThat(ids(adminMemberService.searchMembers("m%f", null, null, NEWEST_FIRST).getContent())).isEmpty();
    }

    private Member save(Long githubId, String login, MemberStatus status, MemberRole role) {
        Member member = Member.registerWithGitHub(githubId, login, null, null);
        ReflectionTestUtils.setField(member, "status", status);
        ReflectionTestUtils.setField(member, "role", role);
        // 가입 시각이 같아도 정렬의 id 보조 조건으로 나중에 저장한 회원이 먼저 온다.
        return memberRepository.saveAndFlush(member);
    }

    private List<Long> ids(List<Member> members) {
        return members.stream().map(Member::getId).toList();
    }
}
