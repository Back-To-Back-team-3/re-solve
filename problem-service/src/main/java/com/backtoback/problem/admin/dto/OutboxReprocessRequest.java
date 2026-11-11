package com.backtoback.problem.admin.dto;

import jakarta.validation.constraints.NotBlank;

public record OutboxReprocessRequest(@NotBlank String reason) {
}
