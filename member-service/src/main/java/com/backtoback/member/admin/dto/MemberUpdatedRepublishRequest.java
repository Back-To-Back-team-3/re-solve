package com.backtoback.member.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원 정보 이벤트 재발행 요청 (API 명세서 1.6.4). 사유는 감사 로그에 남긴다.
 */
public record MemberUpdatedRepublishRequest(
    @NotBlank(message = "사유는 필수입니다.")
    @Size(
        max = 500,
        message = "사유는 500자 이하로 입력해 주세요."
    ) String reason
) {

}
