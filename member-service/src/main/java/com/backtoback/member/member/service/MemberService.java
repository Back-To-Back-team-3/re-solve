package com.backtoback.member.member.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.member.auth.client.GitHubUser;
import com.backtoback.member.global.error.BusinessException;
import com.backtoback.member.global.error.ErrorCode;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;

    /**
     * GitHub 사용자 ID로 회원을 찾고, 없으면 가입시킨다.
     * 같은 GitHub 계정의 동시 가입은 {@code uk_members_github_id}가 막고, 위반 시 먼저 가입된 회원으로 로그인한다.
     * 저장을 별도 트랜잭션으로 끝내야 유일 제약 위반 뒤 다시 조회할 수 있으므로 이 메서드에는 트랜잭션을 걸지 않는다.
     */
    public GitHubMemberRegistration findOrRegister(GitHubUser gitHubUser) {
        GitHubMemberRegistration registration
            = memberRepository
                .findByGithubId(gitHubUser.id())
                .map(member -> new GitHubMemberRegistration(member, false))
                .orElseGet(() -> register(gitHubUser));

        if (registration.member().isWithdrawn()) {
            // 탈퇴 회원의 재가입 정책이 확정되기 전까지 같은 GitHub 계정의 로그인을 막는다.
            throw new BusinessException(ErrorCode.AUTH_ACCESS_DENIED);
        }
        return registration;
    }

    @Transactional(readOnly = true)
    public Member getMember(Long memberId) {
        return memberRepository.findById(memberId).orElseThrow(() -> new BusinessException(ErrorCode.MEMBER_NOT_FOUND));
    }

    private GitHubMemberRegistration register(GitHubUser gitHubUser) {
        Member member
            = Member
                .registerWithGitHub(gitHubUser.id(), gitHubUser.login(), gitHubUser.email(), gitHubUser.avatarUrl());
        try {
            return new GitHubMemberRegistration(memberRepository.saveAndFlush(member), true);
        } catch (DataIntegrityViolationException exception) {
            Member registered = memberRepository.findByGithubId(gitHubUser.id()).orElseThrow(() -> exception);
            return new GitHubMemberRegistration(registered, false);
        }
    }
}
