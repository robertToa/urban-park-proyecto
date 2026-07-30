package com.urbanpark.auth.controller;

import com.urbanpark.auth.dto.ChangePasswordDto;
import com.urbanpark.auth.dto.UpdatePerfilDto;
import com.urbanpark.auth.dto.UserResponseDto;
import com.urbanpark.auth.service.KeycloakAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/perfil")
@RequiredArgsConstructor
public class PerfilController {

    private final KeycloakAdminService keycloakAdminService;

    @GetMapping("/me")
    public UserResponseDto me(@AuthenticationPrincipal Jwt jwt) {
        return keycloakAdminService.getUserByUsername(jwt.getClaimAsString("preferred_username"));
    }

    @PutMapping("/me")
    public UserResponseDto actualizar(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdatePerfilDto request
    ) {
        UserResponseDto actual = keycloakAdminService.getUserByUsername(jwt.getClaimAsString("preferred_username"));
        keycloakAdminService.updateUser(
                actual.id(),
                request.email(),
                request.firstName(),
                request.lastName(),
                actual.enabled()
        );
        return keycloakAdminService.getUser(actual.id());
    }

    @PutMapping("/me/password")
    public Map<String, String> password(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordDto request
    ) {
        String username = jwt.getClaimAsString("preferred_username");
        keycloakAdminService.changePassword(username, request.currentPassword(), request.newPassword());
        return Map.of("message", "Contraseña cambiada");
    }
}
