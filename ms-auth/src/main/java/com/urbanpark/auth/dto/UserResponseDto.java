package com.urbanpark.auth.dto;

import java.util.List;

public record UserResponseDto(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        Boolean enabled,
        List<String> roles
) {}
