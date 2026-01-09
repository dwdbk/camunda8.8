package com.example.camunda.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Service for mapping user identity from LSCSAD realm to Camunda user identity.
 * Extracts user information from JWT token and maps to Camunda user identifier.
 */
@Slf4j
@Service
public class UserIdentityMappingService {
    
    /**
     * Map authenticated user from LSCSAD realm token to Camunda user identifier.
     * 
     * Mapping strategy:
     * 1. Try email (most reliable if users have same email in both realms)
     * 2. Fallback to preferred_username
     * 3. Fallback to subject (sub) claim
     * 
     * @param jwt JWT token from LSCSAD realm
     * @return Camunda user identifier
     */
    public String mapToCamundaUser(Jwt jwt) {
        // Strategy 1: Use email if available
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isEmpty()) {
            log.debug("Mapping user by email: {}", email);
            return email;
        }
        
        // Strategy 2: Use preferred_username
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        if (preferredUsername != null && !preferredUsername.isEmpty()) {
            log.debug("Mapping user by preferred_username: {}", preferredUsername);
            return preferredUsername;
        }
        
        // Strategy 3: Use subject (sub) claim
        String sub = jwt.getClaimAsString("sub");
        if (sub != null && !sub.isEmpty()) {
            log.debug("Mapping user by subject: {}", sub);
            return sub;
        }
        
        // Fallback: Extract username from token ID
        String username = jwt.getClaimAsString("username");
        if (username != null && !username.isEmpty()) {
            log.debug("Mapping user by username: {}", username);
            return username;
        }
        
        throw new IllegalStateException("Unable to extract user identity from JWT token");
    }
    
    /**
     * Get user email from JWT token.
     * 
     * @param jwt JWT token
     * @return Optional email address
     */
    public Optional<String> getUserEmail(Jwt jwt) {
        return Optional.ofNullable(jwt.getClaimAsString("email"));
    }
    
    /**
     * Get user display name from JWT token.
     * 
     * @param jwt JWT token
     * @return Optional display name
     */
    public Optional<String> getUserDisplayName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isEmpty()) {
            return Optional.of(name);
        }
        
        // Fallback to given_name + family_name
        String givenName = jwt.getClaimAsString("given_name");
        String familyName = jwt.getClaimAsString("family_name");
        if (givenName != null || familyName != null) {
            String fullName = String.format("%s %s", 
                givenName != null ? givenName : "", 
                familyName != null ? familyName : "").trim();
            if (!fullName.isEmpty()) {
                return Optional.of(fullName);
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Check if user has a specific role in the JWT token.
     * 
     * @param jwt JWT token
     * @param role Role name to check
     * @return true if user has the role
     */
    public boolean hasRole(Jwt jwt, String role) {
        // Check realm_access.roles
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null) {
            @SuppressWarnings("unchecked")
            java.util.List<String> roles = (java.util.List<String>) realmAccess.get("roles");
            if (roles != null && roles.contains(role)) {
                return true;
            }
        }
        
        // Check resource_access if needed
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        if (resourceAccess != null) {
            // Check specific client roles if needed
            // Example: resourceAccess.get("angular-app").roles
        }
        
        return false;
    }
}
