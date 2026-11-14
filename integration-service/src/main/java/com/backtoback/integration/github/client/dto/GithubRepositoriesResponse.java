package com.backtoback.integration.github.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub 설치 저장소 목록 응답 (GET /installation/repositories).
 */
public record GithubRepositoriesResponse(
    @JsonProperty("total_count") int totalCount,
    List<GithubRepositoryDto> repositories
) {
}
