package com.backtoback.member.admin.dto;

import com.backtoback.member.member.domain.MemberRole;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 회원 권한 변경 요청 (API 명세서 1.6.3). 사유는 감사 로그에 남긴다.
 */
public record MemberRoleChangeRequest(
    @NotNull(message = "변경할 권한은 필수입니다.") MemberRole role,
    @NotBlank(message = "사유는 필수입니다.")
    @Size(
        max = 500,
        message = "사유는 500자 이하로 입력해 주세요."
    ) String reason
) {

}
