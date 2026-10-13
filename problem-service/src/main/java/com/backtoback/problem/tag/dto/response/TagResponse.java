package com.backtoback.problem.tag.dto.response;

import com.backtoback.problem.tag.entity.Tag;

public record TagResponse(String tagId, String name) {
    /**
     * 태그 엔티티를 ID와 이름으로 구성된 응답으로 변환한다.
     *
     * @param tag 응답으로 변환할 태그
     * @return 문자열 ID와 이름을 담은 태그 응답
     */
    public static TagResponse from(Tag tag) {
        return new TagResponse(tag.getId().toString(), tag.getName());
    }
}
