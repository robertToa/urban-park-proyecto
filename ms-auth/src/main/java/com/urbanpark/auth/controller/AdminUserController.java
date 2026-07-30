package com.urbanpark.auth.controller;

import com.urbanpark.auth.dto.*;
import com.urbanpark.auth.service.KeycloakAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final KeycloakAdminService keycloakAdminService;

    @GetMapping
    public List<UserResponseDto> listar() {
        return keycloakAdminService.listUsers();
    }

    @GetMapping("/{id}")
    public UserResponseDto obtener(@PathVariable String id) {
        return keycloakAdminService.getUser(id);
    }

    @PostMapping
    public ResponseEntity<UserResponseDto> crear(@Valid @RequestBody CreateUserDto request) {
        String userId = keycloakAdminService.createUser(
                request.username(),
                request.email(),
                request.firstName(),
                request.lastName(),
                request.password()
        );
        keycloakAdminService.replaceRealmRoles(userId, request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(keycloakAdminService.getUser(userId));
    }

    @PutMapping("/{id}")
    public UserResponseDto actualizar(@PathVariable String id, @Valid @RequestBody UpdateUserDto request) {
        keycloakAdminService.updateUser(
                id,
                request.email(),
                request.firstName(),
                request.lastName(),
                request.enabled()
        );
        return keycloakAdminService.getUser(id);
    }

    @PutMapping("/{id}/roles")
    public UserResponseDto roles(@PathVariable String id, @Valid @RequestBody UpdateRolesDto request) {
        keycloakAdminService.replaceRealmRoles(id, request.role());
        return keycloakAdminService.getUser(id);
    }

    @PutMapping("/{id}/password")
    public Map<String, String> password(@PathVariable String id, @Valid @RequestBody ResetPasswordDto request) {
        keycloakAdminService.resetPassword(id, request.password());
        return Map.of("message", "Contraseña actualizada");
    }
}
