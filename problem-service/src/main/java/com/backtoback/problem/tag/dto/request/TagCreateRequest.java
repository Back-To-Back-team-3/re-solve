package com.backtoback.problem.tag.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TagCreateRequest(@NotBlank @Size(max = 200) String name, @NotNull Integer displayOrder) {
}
