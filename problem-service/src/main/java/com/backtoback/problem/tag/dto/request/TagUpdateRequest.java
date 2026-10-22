package com.backtoback.problem.tag.dto.request;

import com.backtoback.problem.global.validation.NullOrNotBlank;

import jakarta.validation.constraints.Size;

/** 태그 수정에 필요한 선택적 이름과 표시 순번을 검증한다. */
public record TagUpdateRequest(
    @NullOrNotBlank
    @Size(
        min = 1,
        max = 200,
        message = "태그 이름은 1자 이상 200자 이하로 입력해 주세요."
    ) String name,
    Integer displayOrder
) {
}
