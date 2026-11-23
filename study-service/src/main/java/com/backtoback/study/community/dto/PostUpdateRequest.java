package com.backtoback.study.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 게시글 수정 요청 바디 (API 명세서 5.5.4). 검증 기준은 작성(5.5.2)과 같다.
 */
public record PostUpdateRequest(
    @NotBlank(message = "제목을 입력해 주세요.")
    @Size(
        max = 200,
        message = "제목은 200자 이하로 입력해 주세요."
    ) String title,
    @NotBlank(message = "본문을 입력해 주세요.")
    @Size(
        max = 10000,
        message = "본문은 10,000자 이하로 입력해 주세요."
    ) String content
) {
}
