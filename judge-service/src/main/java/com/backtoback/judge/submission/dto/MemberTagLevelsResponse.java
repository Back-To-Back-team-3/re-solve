package com.backtoback.judge.submission.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 태그 레벨 조회 응답이다.
 *
 * @param memberId 회원 ID
 * @param tagLevels 진입한 태그 레벨
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberTagLevelsResponse(String memberId, List<TagLevel> tagLevels) {

    /**
     * 한 태그의 레벨이다.
     *
     * @param tagId 태그 ID
     * @param level {@code LV0}부터 {@code LV3} 또는 {@code MASTER}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TagLevel(String tagId, String level) {
    }
}
