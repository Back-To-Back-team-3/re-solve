package com.backtoback.problem.tag.dto.request;

import jakarta.validation.constraints.Size;

public record TagUpdateRequest(
    @Size(
        min = 1,
        max = 200
    ) String name,
    Integer displayOrder
) {
}
