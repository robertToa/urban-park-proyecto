package com.urbanpark.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UpdateUserDto(
        @NotBlank @Email String email,
        @NotBlank String firstName,
        @NotBlank String lastName,
        Boolean enabled
) {}
