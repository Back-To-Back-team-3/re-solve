package com.backtoback.member.member.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.auth.client.GitHubUser;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;
import com.backtoback.member.member.client.WithdrawalCheckClient;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.repository.MemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberService {

    private static final int MIN_NICKNAME_LENGTH = 2;
    private static final int MAX_NICKNAME_LENGTH = 50;
    private static final int ASCII_MAX = 0x7F;

    private final MemberRepository memberRepository;
    private final MemberEventPublisher memberEventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final WithdrawalCheckClient withdrawalCheckClient;
    private final Clock clock;

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
            throw new MemberException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        return registration;
    }

    @Transactional(readOnly = true)
    public Member getMember(Long memberId) {
        return memberRepository
            .findById(memberId)
            .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    /**
     * 닉네임을 변경한다 (API 명세서 1.2.2). {@code null}이면 바꾸지 않는다. 앞뒤 공백은 지우고 이모지 없는 2~50자인지 확인한다.
     */
    @Transactional
    public Member updateNickname(Long memberId, String nickname) {
        Member member = lockActiveMember(memberId);
        if (nickname != null) {
            String trimmed = nickname.strip();
            int length = trimmed.codePointCount(0, trimmed.length());
            if (length < MIN_NICKNAME_LENGTH || length > MAX_NICKNAME_LENGTH
                || trimmed.codePoints().anyMatch(MemberService::isEmoji)) {
                throw new MemberException(MemberErrorCode.MEMBER_NICKNAME_INVALID);
            }
            if (member.changeNickname(trimmed)) {
                memberEventPublisher.memberUpdated(member);
            }
        }
        return member;
    }

    /**
     * 회원의 쓰기 요청용 조회. 같은 회원의 변경({@code profile_version} 증가)이 겹치지 않도록 행을 잠그고, 정지 회원은 403이다.
     */
    @Transactional
    public Member lockActiveMember(Long memberId) {
        Member member
            = memberRepository
                .findForUpdateById(memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
        if (member.isSuspended()) {
            throw new MemberException(MemberErrorCode.MEMBER_SUSPENDED);
        }
        return member;
    }

    /**
     * 회원 탈퇴 (API 명세서 1.2.3). 원격 확인은 회원 행을 잠그기 전에 끝내고, 통과하면 한 트랜잭션에서 {@code WITHDRAWN}
     * 전이·익명화·{@code MemberWithdrawn} 기록을 한다. 이미 탈퇴했으면 아무것도 하지 않는다.
     */
    public void withdraw(Long memberId) {
        if (getMember(memberId).isWithdrawn()) {
            return;
        }
        // shortcut: 확인과 탈퇴 사이에 시험에 입장하는 경우는 막지 않음(참가는 MemberWithdrawn으로 익명화), contest 자원 가드(4.7.3·4.7.4)를
        // 도입할 때 prepare·complete로 바꾼다.
        if (withdrawalCheckClient.hasActiveExam(memberId)) {
            throw new MemberException(MemberErrorCode.WITHDRAWAL_BLOCKED_ACTIVE_EXAM);
        }
        if (withdrawalCheckClient.isStudyLeader(memberId)) {
            throw new MemberException(MemberErrorCode.WITHDRAWAL_BLOCKED_STUDY_LEADER);
        }
        transactionTemplate.executeWithoutResult(status -> {
            Member member
                = memberRepository
                    .findForUpdateById(memberId)
                    .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
            if (member.isWithdrawn()) {
                return;
            }
            member.withdraw(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
            memberEventPublisher.memberWithdrawn(member);
        });
    }

    @Transactional(readOnly = true)
    public Optional<Member> findMember(Long memberId) {
        return memberRepository.findById(memberId);
    }

    /**
     * 이모지와 이모지를 이루는 문자(국기·피부색·키캡·조합용 문자)인지 판정한다. ASCII 숫자·{@code #}·{@code *}는 허용한다.
     */
    private static boolean isEmoji(int codePoint) {
        return Character.isExtendedPictographic(codePoint)
            || (Character.isEmojiComponent(codePoint) && codePoint > ASCII_MAX);
    }

    private GitHubMemberRegistration register(GitHubUser gitHubUser) {
        Member member
            = Member
                .registerWithGitHub(gitHubUser.id(), gitHubUser.login(), gitHubUser.email(), gitHubUser.avatarUrl());
        try {
            // 가입과 MemberUpdated 기록을 한 트랜잭션으로 묶는다. 유일 제약 위반이면 함께 롤백되고 아래에서 다시 조회한다.
            Member saved = transactionTemplate.execute(status -> {
                Member inserted = memberRepository.saveAndFlush(member);
                memberEventPublisher.memberUpdated(inserted);
                return inserted;
            });
            return new GitHubMemberRegistration(saved, true);
        } catch (DataIntegrityViolationException exception) {
            Member registered = memberRepository.findByGithubId(gitHubUser.id()).orElseThrow(() -> exception);
            return new GitHubMemberRegistration(registered, false);
        }
    }
}
