package com.backtoback.member.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.auth.client.GitHubUser;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.member.domain.Member;
import com.backtoback.member.member.domain.MemberRole;
import com.backtoback.member.member.domain.MemberStatus;
import com.backtoback.member.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    private static final GitHubUser GITHUB_USER
        = new GitHubUser(1001L, "kim-dev", "kim@example.com", "https://avatars.githubusercontent.com/u/1001");

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private MemberService memberService;

    @Test
    @DisplayName("처음 로그인한 GitHub 계정은 USER·ACTIVE 회원으로 가입시킨다")
    void registersNewMember() {
        given(memberRepository.findByGithubId(GITHUB_USER.id())).willReturn(Optional.empty());
        given(memberRepository.saveAndFlush(any(Member.class))).willAnswer(invocation -> invocation.getArgument(0));

        GitHubMemberRegistration registration = memberService.findOrRegister(GITHUB_USER);

        Member member = registration.member();
        assertThat(registration.newMember()).isTrue();
        assertThat(member.getGithubId()).isEqualTo(1001L);
        assertThat(member.getGithubLogin()).isEqualTo("kim-dev");
        assertThat(member.getNickname()).isEqualTo("kim-dev");
        assertThat(member.getEmail()).isEqualTo("kim@example.com");
        assertThat(member.getRole()).isEqualTo(MemberRole.USER);
        assertThat(member.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(member.getProfileVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("이미 가입한 GitHub 계정은 기존 회원으로 로그인한다")
    void returnsExistingMember() {
        Member existing = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        given(memberRepository.findByGithubId(GITHUB_USER.id())).willReturn(Optional.of(existing));

        GitHubMemberRegistration registration = memberService.findOrRegister(GITHUB_USER);

        assertThat(registration.newMember()).isFalse();
        assertThat(registration.member()).isSameAs(existing);
        verify(memberRepository, never()).saveAndFlush(any(Member.class));
    }

    @Test
    @DisplayName("동시 가입으로 유일 제약을 위반하면 먼저 가입된 회원으로 로그인한다")
    void fallsBackToExistingMemberOnDuplicateSignup() {
        Member registeredConcurrently = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        given(memberRepository.findByGithubId(GITHUB_USER.id()))
            .willReturn(Optional.empty())
            .willReturn(Optional.of(registeredConcurrently));
        given(memberRepository.saveAndFlush(any(Member.class)))
            .willThrow(new DataIntegrityViolationException("uk_members_github_id"));

        GitHubMemberRegistration registration = memberService.findOrRegister(GITHUB_USER);

        assertThat(registration.newMember()).isFalse();
        assertThat(registration.member()).isSameAs(registeredConcurrently);
    }

    @Test
    @DisplayName("탈퇴한 회원의 GitHub 계정으로는 로그인할 수 없다")
    void rejectsWithdrawnMember() {
        Member withdrawn = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        ReflectionTestUtils.setField(withdrawn, "status", MemberStatus.WITHDRAWN);
        given(memberRepository.findByGithubId(GITHUB_USER.id())).willReturn(Optional.of(withdrawn));

        assertThatThrownBy(() -> memberService.findOrRegister(GITHUB_USER))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED);
    }

    @Test
    @DisplayName("닉네임 수정: 앞뒤 공백을 지워 저장하고 profileVersion을 1 올린다")
    void updateNicknameTrimsAndBumpsVersion() {
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        given(memberRepository.findForUpdateById(7L)).willReturn(Optional.of(member));

        memberService.updateNickname(7L, "  kim-algo  ");

        assertThat(member.getNickname()).isEqualTo("kim-algo");
        assertThat(member.getProfileVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("닉네임 수정: 닉네임이 없거나 같으면 바꾸지 않고 profileVersion도 그대로 둔다")
    void updateNicknameKeepsVersionWhenUnchanged() {
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        given(memberRepository.findForUpdateById(7L)).willReturn(Optional.of(member));

        memberService.updateNickname(7L, null);
        memberService.updateNickname(7L, "kim-dev");

        assertThat(member.getNickname()).isEqualTo("kim-dev");
        assertThat(member.getProfileVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("닉네임 수정: 공백을 지운 길이가 2자 미만이거나 50자를 넘으면 MEMBER_NICKNAME_INVALID")
    void updateNicknameRejectsInvalidLength() {
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        given(memberRepository.findForUpdateById(7L)).willReturn(Optional.of(member));

        for (String invalid : new String[] {
            "a",
            "   ",
            " a ",
            "a".repeat(51)
        }) {
            assertThatThrownBy(() -> memberService.updateNickname(7L, invalid))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MemberErrorCode.MEMBER_NICKNAME_INVALID);
        }
        // 보조 문자(한자 확장)도 한 글자로 센다.
        memberService.updateNickname(7L, "𠀀".repeat(50));
        assertThat(member.getNickname()).isEqualTo("𠀀".repeat(50));
    }

    @Test
    @DisplayName("닉네임 수정: 이모지·국기·피부색·키캡·이모지 조합이 들어가면 MEMBER_NICKNAME_INVALID")
    void updateNicknameRejectsEmoji() {
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        given(memberRepository.findForUpdateById(7L)).willReturn(Optional.of(member));

        for (String invalid : new String[] {
            "kim😀",
            "🇰🇷kim",
            "👍🏻👍🏻",
            "kim1\uFE0F\u20E3",
            "👨\u200D👩\u200D👧",
            "kim♥"
        }) {
            assertThatThrownBy(() -> memberService.updateNickname(7L, invalid))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MemberErrorCode.MEMBER_NICKNAME_INVALID);
        }
        assertThat(member.getNickname()).isEqualTo("kim-dev");
    }

    @Test
    @DisplayName("닉네임 수정: 한글·영문·숫자·기호(#·*·-·_)는 허용한다")
    void updateNicknameAllowsText() {
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        given(memberRepository.findForUpdateById(7L)).willReturn(Optional.of(member));

        for (String valid : new String[] {
            "김알고",
            "kim_01",
            "#1 kim*",
            "あいう"
        }) {
            memberService.updateNickname(7L, valid);
            assertThat(member.getNickname()).isEqualTo(valid);
        }
    }

    @Test
    @DisplayName("닉네임 수정: 정지된 회원이면 MEMBER_SUSPENDED이고 바꾸지 않는다")
    void updateNicknameRejectsSuspendedMember() {
        Member member = Member.registerWithGitHub(1001L, "kim-dev", null, null);
        ReflectionTestUtils.setField(member, "status", MemberStatus.SUSPENDED);
        given(memberRepository.findForUpdateById(7L)).willReturn(Optional.of(member));

        assertThatThrownBy(() -> memberService.updateNickname(7L, "kim-algo"))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.MEMBER_SUSPENDED);
        assertThat(member.getNickname()).isEqualTo("kim-dev");
    }
}
