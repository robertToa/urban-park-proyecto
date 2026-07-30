package com.urbanpark.auth.service;

import com.urbanpark.auth.dto.UserResponseDto;

import java.util.List;

public interface KeycloakAdminService {
    String createUser(String username, String email, String firstName, String lastName, String password);

    void assignRealmRole(String userId, String roleName);

    void replaceRealmRoles(String userId, String roleName);

    List<UserResponseDto> listUsers();

    UserResponseDto getUser(String userId);

    UserResponseDto getUserByUsername(String username);

    void updateUser(String userId, String email, String firstName, String lastName, Boolean enabled);

    void resetPassword(String userId, String password);

    void changePassword(String username, String currentPassword, String newPassword);
}
