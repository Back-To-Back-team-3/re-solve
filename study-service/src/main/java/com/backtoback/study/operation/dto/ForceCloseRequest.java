package com.backtoback.study.operation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 관리자 강제 종료 요청 바디 (API 명세서 §5.6.1 요청 바디).
 */
public record ForceCloseRequest(@NotBlank @Size(max = 500) String reason) {

}
