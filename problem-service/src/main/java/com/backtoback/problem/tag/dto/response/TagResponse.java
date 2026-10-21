package com.backtoback.problem.tag.dto.response;

import com.backtoback.problem.tag.entity.Tag;

/** 태그의 문자열 ID·이름·현재 표시 순번을 담는 API 응답. */
public record TagResponse(String tagId, String name, int displayOrder) {
    /**
     * 태그 엔티티를 ID·이름·현재 순번으로 구성된 응답으로 변환한다.
     *
     * @param tag 응답으로 변환할 태그
     * @return 문자열 ID·이름·현재 순번을 담은 태그 응답
     */
    public static TagResponse from(Tag tag) {
        return new TagResponse(tag.getId().toString(), tag.getName(), tag.getDisplayOrder());
    }
}
