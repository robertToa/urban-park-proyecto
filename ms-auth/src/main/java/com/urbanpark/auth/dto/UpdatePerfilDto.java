package com.urbanpark.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record UpdatePerfilDto(
        @NotBlank @Email String email,
        @NotBlank String firstName,
        @NotBlank String lastName
) {}
