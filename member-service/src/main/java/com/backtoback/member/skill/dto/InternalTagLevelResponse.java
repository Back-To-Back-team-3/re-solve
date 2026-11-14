package com.backtoback.member.skill.dto;

import java.util.List;

import com.backtoback.member.skill.domain.MemberTagLevel;

/**
 * 내부 태그 레벨 조회 응답 (API 명세서 1.7.2). 내부 API라 공통 응답으로 감싸지 않는다(API 명세서 0.3).
 */
public record InternalTagLevelResponse(String memberId, List<TagLevelItem> tagLevels) {

    public static InternalTagLevelResponse from(Long memberId, List<MemberTagLevel> tagLevels) {
        return new InternalTagLevelResponse(
            String.valueOf(memberId),
            tagLevels.stream().map(TagLevelItem::from).toList()
        );
    }

    public record TagLevelItem(String tagId, String level) {

        static TagLevelItem from(MemberTagLevel tagLevel) {
            return new TagLevelItem(String.valueOf(tagLevel.getTagId()), tagLevel.getLevel().name());
        }
    }
}
