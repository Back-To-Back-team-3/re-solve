package com.backtoback.problem.tag.dto.request;

import com.backtoback.problem.global.validation.NullOrNotBlank;

import jakarta.validation.constraints.Size;

public record TagUpdateRequest(
    @NullOrNotBlank
    @Size(
        min = 1,
        max = 200
    ) String name,
    Integer displayOrder
) {
}
