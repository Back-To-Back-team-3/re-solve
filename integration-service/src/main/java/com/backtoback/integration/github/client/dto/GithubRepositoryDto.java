package com.backtoback.integration.github.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub 저장소 정보 DTO.
 */
public record GithubRepositoryDto(Long id, String name, @JsonProperty("full_name") String fullName, Owner owner) {

    public record Owner(Long id, String login) {
    }
}
