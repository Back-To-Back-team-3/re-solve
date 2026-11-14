package com.backtoback.member.skill.dto;

import com.backtoback.member.skill.domain.MemberTagLevel;

/**
 * 태그 진입 응답 (API 명세서 1.4.2).
 */
public record TagEntryResponse(String tagId, String level) {

    public static TagEntryResponse from(MemberTagLevel tagLevel) {
        return new TagEntryResponse(String.valueOf(tagLevel.getTagId()), tagLevel.getLevel().name());
    }
}
