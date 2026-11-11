package com.backtoback.problem.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record OutboxReprocessRequest(@NotBlank(message = "재처리 사유를 입력해 주세요.") String reason) {
}
