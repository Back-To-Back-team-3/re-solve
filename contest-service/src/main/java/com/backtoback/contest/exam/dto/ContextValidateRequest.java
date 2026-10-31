package com.backtoback.contest.exam.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 실행 문맥·열람 권한 검증 입력. (API 명세서 §4.7.1)
 */
public record ContextValidateRequest(
    @NotBlank(message = "문맥은 필수입니다.")
    @Pattern(
        regexp = "EXAM|CONTEST",
        message = "문맥은 EXAM 또는 CONTEST여야 합니다."
    ) String context,
    @NotBlank(message = "문맥 ID는 필수입니다.") String contextId,
    @NotBlank(message = "회원 ID는 필수입니다.") String memberId,
    @NotBlank(message = "문제 ID는 필수입니다.") String problemId,
    @NotBlank(message = "문제 회차 ID는 필수입니다.") String problemRevisionId,
    @NotBlank(message = "행위는 필수입니다.")
    @Pattern(
        regexp = "READ_BODY|READ_IMAGE|RUN|READ_SUBMISSION",
        message = "지원하는 행위가 아닙니다."
    ) String action,
    String receiptId
) {
    @JsonIgnore
    @AssertTrue(message = "제출 열람에는 접수 ID가 필수입니다.")
    public boolean isReceiptPresentForSubmission() {
        return !"READ_SUBMISSION".equals(action) || (receiptId != null && !receiptId.isBlank());
    }
}
