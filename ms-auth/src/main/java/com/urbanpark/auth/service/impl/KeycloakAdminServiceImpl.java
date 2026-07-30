package com.urbanpark.auth.service.impl;

import com.urbanpark.auth.dto.UserResponseDto;
import com.urbanpark.auth.service.KeycloakAdminService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class KeycloakAdminServiceImpl implements KeycloakAdminService {

    private static final Set<String> APP_ROLES = Set.of("CLIENTE", "OPERADOR", "ADMIN");

    private final WebClient webClient;

    @Value("${keycloak.server-url}")
    private String serverUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.admin-client-id}")
    private String adminClientId;

    @Value("${keycloak.admin-client-secret}")
    private String adminClientSecret;

    public KeycloakAdminServiceImpl(WebClient webClient) {
        this.webClient = webClient;
    }

    private String getServiceAccountToken() {
        String tokenUrl = serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "client_credentials");
        formData.add("client_id", adminClientId);
        formData.add("client_secret", adminClientSecret);

        try {
            Map<String, Object> response = webClient.post()
                    .uri(tokenUrl)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .body(BodyInserters.fromFormData(formData))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
            if (response == null || response.get("access_token") == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo obtener token de servicio Keycloak");
            }
            return (String) response.get("access_token");
        } catch (WebClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Keycloak Admin token falló: " + ex.getStatusCode());
        }
    }

    @Override
    public String createUser(String username, String email, String firstName, String lastName, String password) {
        String token = getServiceAccountToken();
        String createUserUrl = serverUrl + "/admin/realms/" + realm + "/users";
        Map<String, Object> credentials = Map.of(
                "type", "password",
                "value", password,
                "temporary", false
        );
        Map<String, Object> userPayload = new HashMap<>();
        userPayload.put("username", username);
        userPayload.put("email", email);
        userPayload.put("firstName", firstName);
        userPayload.put("lastName", lastName);
        userPayload.put("enabled", true);
        userPayload.put("emailVerified", true);
        userPayload.put("credentials", List.of(credentials));

        try {
            webClient.post()
                    .uri(createUserUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(userPayload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException.Conflict ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El usuario " + username + " ya existe");
        } catch (WebClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error creando usuario: " + ex.getResponseBodyAsString());
        }
        return getUserIdByUsername(username, token);
    }

    @Override
    public void assignRealmRole(String userId, String roleName) {
        String token = getServiceAccountToken();
        Map<String, Object> role = getRealmRole(roleName, token);
        String assignUrl = serverUrl + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm";
        webClient.post()
                .uri(assignUrl)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(role))
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    @Override
    public void replaceRealmRoles(String userId, String roleName) {
        if (!APP_ROLES.contains(roleName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rol no permitido: " + roleName);
        }
        String token = getServiceAccountToken();
        List<Map<String, Object>> current = getUserRealmRoles(userId, token);
        List<Map<String, Object>> toRemove = current.stream()
                .filter(r -> APP_ROLES.contains(String.valueOf(r.get("name"))))
                .collect(Collectors.toList());
        if (!toRemove.isEmpty()) {
            webClient.method(org.springframework.http.HttpMethod.DELETE)
                    .uri(serverUrl + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(toRemove)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        }
        assignRealmRole(userId, roleName);
        // ADMIN suele llevar también OPERADOR para operaciones de plaza
        if ("ADMIN".equals(roleName)) {
            assignRealmRole(userId, "OPERADOR");
        }
    }

    @Override
    public List<UserResponseDto> listUsers() {
        String token = getServiceAccountToken();
        List<Map<String, Object>> users = webClient.get()
                .uri(serverUrl + "/admin/realms/" + realm + "/users?max=200")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block();
        if (users == null) return List.of();
        List<UserResponseDto> out = new ArrayList<>();
        for (Map<String, Object> u : users) {
            String username = String.valueOf(u.get("username"));
            if (username.startsWith("service-account-")) continue;
            out.add(toDto(u, token));
        }
        return out;
    }

    @Override
    public UserResponseDto getUser(String userId) {
        String token = getServiceAccountToken();
        Map<String, Object> user = webClient.get()
                .uri(serverUrl + "/admin/realms/" + realm + "/users/" + userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado");
        }
        return toDto(user, token);
    }

    @Override
    public UserResponseDto getUserByUsername(String username) {
        String token = getServiceAccountToken();
        String id = getUserIdByUsername(username, token);
        return getUser(id);
    }

    @Override
    public void updateUser(String userId, String email, String firstName, String lastName, Boolean enabled) {
        String token = getServiceAccountToken();
        Map<String, Object> current = webClient.get()
                .uri(serverUrl + "/admin/realms/" + realm + "/users/" + userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", current.get("username"));
        payload.put("email", email);
        payload.put("firstName", firstName);
        payload.put("lastName", lastName);
        payload.put("enabled", enabled != null ? enabled : current.get("enabled"));
        payload.put("emailVerified", true);

        try {
            webClient.put()
                    .uri(serverUrl + "/admin/realms/" + realm + "/users/" + userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException.Conflict ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email ya en uso");
        } catch (WebClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Error actualizando usuario: " + ex.getResponseBodyAsString());
        }
    }

    @Override
    public void resetPassword(String userId, String password) {
        String token = getServiceAccountToken();
        Map<String, Object> cred = Map.of(
                "type", "password",
                "value", password,
                "temporary", false
        );
        webClient.put()
                .uri(serverUrl + "/admin/realms/" + realm + "/users/" + userId + "/reset-password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(cred)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    @Override
    public void changePassword(String username, String currentPassword, String newPassword) {
        // Verifica contraseña actual con ROPC (urbanpark-web no tiene secret)
        String tokenUrl = serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "urbanpark-web");
        form.add("username", username);
        form.add("password", currentPassword);
        try {
            webClient.post()
                    .uri(tokenUrl)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
        } catch (WebClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contraseña actual incorrecta");
        }
        String token = getServiceAccountToken();
        String userId = getUserIdByUsername(username, token);
        resetPassword(userId, newPassword);
    }

    private String getUserIdByUsername(String username, String token) {
        List<Map<String, Object>> users = webClient.get()
                .uri(serverUrl + "/admin/realms/" + realm + "/users?username={u}&exact=true", username)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block();
        if (users == null || users.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado: " + username);
        }
        return (String) users.get(0).get("id");
    }

    private Map<String, Object> getRealmRole(String roleName, String token) {
        try {
            return webClient.get()
                    .uri(serverUrl + "/admin/realms/" + realm + "/roles/" + roleName)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
        } catch (WebClientResponseException.NotFound ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rol no existe: " + roleName);
        }
    }

    private List<Map<String, Object>> getUserRealmRoles(String userId, String token) {
        List<Map<String, Object>> roles = webClient.get()
                .uri(serverUrl + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block();
        return roles != null ? roles : List.of();
    }

    private UserResponseDto toDto(Map<String, Object> u, String token) {
        String id = String.valueOf(u.get("id"));
        List<String> roles = getUserRealmRoles(id, token).stream()
                .map(r -> String.valueOf(r.get("name")))
                .filter(APP_ROLES::contains)
                .sorted()
                .collect(Collectors.toList());
        return new UserResponseDto(
                id,
                str(u.get("username")),
                str(u.get("email")),
                str(u.get("firstName")),
                str(u.get("lastName")),
                u.get("enabled") instanceof Boolean b ? b : Boolean.TRUE.equals(u.get("enabled")),
                roles
        );
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
