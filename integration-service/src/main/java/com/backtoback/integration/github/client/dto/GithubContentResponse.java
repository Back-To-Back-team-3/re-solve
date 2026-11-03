package com.backtoback.integration.github.client.dto;

/**
 * GitHub Contents API 파일 조회 응답 (GET /repos/{owner}/{repo}/contents/{path}).
 * 같은 경로에 새 커밋을 남길 때 필요한 기존 파일 sha만 사용한다.
 */
public record GithubContentResponse(String sha) {
}
