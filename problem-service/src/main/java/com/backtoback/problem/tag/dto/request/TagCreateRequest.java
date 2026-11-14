package com.backtoback.problem.tag.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 태그 생성에 필요한 이름과 표시 순번을 검증한다. */
public record TagCreateRequest(
    @NotBlank(message = "태그 이름을 입력해 주세요.")
    @Size(
        max = 200,
        message = "태그 이름은 200자 이하로 입력해 주세요."
    ) String name,
    @NotNull(message = "표시 순서를 입력해 주세요.") Integer displayOrder
) {
}
