package com.backtoback.member.admin.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberRole;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AdminMemberService {

    private final MemberRepository memberRepository;

    /**
     * 관리자 회원 목록 (API 명세서 1.6.1). {@code keyword}는 닉네임·GitHub 로그인명의 부분 일치로 찾고,
     * 입력한 {@code %}·{@code _}는 와일드카드가 아니라 문자 그대로 찾는다.
     */
    @Transactional(readOnly = true)
    public Page<Member> searchMembers(String keyword, MemberStatus status, MemberRole role, Pageable pageable) {
        String pattern = StringUtils.hasText(keyword) ? escapeLike(keyword.strip()) : null;
        return memberRepository.search(pattern, status, role, pageable);
    }

    private static String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
