package com.backtoback.member.auth.client;

/**
 * GitHub에서 받은 로그인 사용자 정보.
 *
 * @param email GitHub에서 확인된 기본 이메일. 비공개이거나 확인되지 않았으면 {@code null}
 */
public record GitHubUser(Long id, String login, String email, String avatarUrl) {
}
