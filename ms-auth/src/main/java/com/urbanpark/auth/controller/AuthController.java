package com.urbanpark.auth.controller;

import com.urbanpark.auth.dto.RegisterRequestDto;
import com.urbanpark.auth.service.KeycloakAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final KeycloakAdminService keycloakAdminService;

    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequestDto request) {
        String userId = keycloakAdminService.createUser(
                request.username(),
                request.email(),
                request.firstName(),
                request.lastName(),
                request.password()
        );
        keycloakAdminService.replaceRealmRoles(userId, request.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "id", userId,
                "username", request.username(),
                "role", request.role(),
                "message", "Usuario registrado como " + request.role()
        ));
    }
}
