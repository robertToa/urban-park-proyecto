package com.urbanpark.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateRolesDto(
        @NotBlank @Pattern(regexp = "CLIENTE|OPERADOR|ADMIN") String role
) {}
