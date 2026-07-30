package com.urbanpark.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordDto(
        @NotBlank @Size(min = 6, max = 100) String password
) {}
