package com.backtoback.member.member.service;

import com.backtoback.member.member.domain.Member;

/**
 * GitHub 로그인 콜백에서 조회하거나 새로 만든 회원.
 *
 * @param newMember 이번 로그인으로 가입했으면 {@code true}
 */
public record GitHubMemberRegistration(Member member, boolean newMember) {
}
