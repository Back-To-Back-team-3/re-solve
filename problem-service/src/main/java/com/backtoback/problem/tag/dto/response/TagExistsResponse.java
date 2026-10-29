package com.backtoback.problem.tag.dto.response;

import com.backtoback.problem.tag.domain.Tag;

/** 내부 API(2.6.11)가 반환하는 태그의 문자열 ID·이름. */
public record TagExistsResponse(String tagId, String name) {
    /**
     * 태그 엔티티를 ID·이름으로 구성된 내부 응답으로 변환한다.
     *
     * @param tag 응답으로 변환할 태그
     * @return 문자열 ID·이름을 담은 내부 응답
     */
    public static TagExistsResponse from(Tag tag) {
        return new TagExistsResponse(tag.getId().toString(), tag.getName());
    }
}
