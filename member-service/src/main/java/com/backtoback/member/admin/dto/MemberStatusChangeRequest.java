package com.backtoback.member.admin.dto;

import com.backtoback.member.member.domain.MemberStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 회원 정지·해제 요청 (API 명세서 1.6.2). 사유는 감사 로그에 남긴다.
 */
public record MemberStatusChangeRequest(
    @NotNull(message = "변경할 상태는 필수입니다.") MemberStatus status,
    @NotBlank(message = "사유는 필수입니다.")
    @Size(
        max = 500,
        message = "사유는 500자 이하로 입력해 주세요."
    ) String reason
) {

}
