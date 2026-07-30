package com.urbanpark.tickets.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

public final class AuthSupport {

    private AuthSupport() {}

    public static String username(Jwt jwt) {
        if (jwt == null) return null;
        String u = jwt.getClaimAsString("preferred_username");
        return u != null ? u.trim() : null;
    }

    @SuppressWarnings("unchecked")
    public static boolean hasRole(Jwt jwt, String role) {
        if (jwt == null) return false;
        Map<String, Object> realm = jwt.getClaim("realm_access");
        if (realm == null || realm.get("roles") == null) return false;
        return ((List<String>) realm.get("roles")).contains(role);
    }

    /** ADMIN > OPERADOR > CLIENTE */
    public static String rolPrincipal(Jwt jwt) {
        if (hasRole(jwt, "ADMIN")) return "ADMIN";
        if (hasRole(jwt, "OPERADOR")) return "OPERADOR";
        if (hasRole(jwt, "CLIENTE")) return "CLIENTE";
        return null;
    }

    public static boolean esClienteSolo(Jwt jwt) {
        return "CLIENTE".equals(rolPrincipal(jwt));
    }
}
