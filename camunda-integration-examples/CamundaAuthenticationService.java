package com.example.camunda.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for authenticating with Camunda 8.8 using Keycloak camunda-platform realm.
 * Uses client credentials grant to obtain access tokens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CamundaAuthenticationService {

    private final WebClient webClient;
    
    @Value("${camunda.keycloak.url}")
    private String keycloakUrl;
    
    @Value("${camunda.keycloak.realm}")
    private String realm;
    
    @Value("${camunda.keycloak.client-id}")
    private String clientId;
    
    @Value("${camunda.keycloak.client-secret}")
    private String clientSecret;
    
    private AccessToken cachedToken;
    private LocalDateTime tokenExpiry;
    
    /**
     * Get a valid access token for Camunda API calls.
     * Returns cached token if still valid, otherwise refreshes.
     */
    public String getAccessToken() {
        if (cachedToken == null || isTokenExpired()) {
            refreshToken();
        }
        return cachedToken.getToken();
    }
    
    /**
     * Check if the cached token is expired or will expire soon (within 1 minute).
     */
    private boolean isTokenExpired() {
        if (tokenExpiry == null) {
            return true;
        }
        // Refresh if token expires within 1 minute
        return LocalDateTime.now().isAfter(tokenExpiry.minusMinutes(1));
    }
    
    /**
     * Refresh the access token using client credentials grant.
     */
    private void refreshToken() {
        log.debug("Refreshing Camunda access token");
        
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", 
            keycloakUrl, realm);
        
        Map<String, String> formData = new HashMap<>();
        formData.put("grant_type", "client_credentials");
        formData.put("client_id", clientId);
        formData.put("client_secret", clientSecret);
        
        TokenResponse response = webClient.post()
            .uri(tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(formData))
            .retrieve()
            .bodyToMono(TokenResponse.class)
            .block();
        
        if (response == null || response.getAccessToken() == null) {
            throw new RuntimeException("Failed to obtain Camunda access token");
        }
        
        cachedToken = new AccessToken(response.getAccessToken());
        // Set expiry time (typically tokens expire in 5 minutes, but use actual expiry from response)
        int expiresIn = response.getExpiresIn() != null ? response.getExpiresIn() : 300;
        tokenExpiry = LocalDateTime.now().plusSeconds(expiresIn);
        
        log.debug("Successfully refreshed Camunda access token, expires at: {}", tokenExpiry);
    }
    
    /**
     * Clear cached token (useful for testing or forced refresh).
     */
    public void clearCache() {
        cachedToken = null;
        tokenExpiry = null;
    }
    
    /**
     * Inner class to hold access token.
     */
    private static class AccessToken {
        private final String token;
        
        AccessToken(String token) {
            this.token = token;
        }
        
        String getToken() {
            return token;
        }
    }
    
    /**
     * Token response DTO from Keycloak.
     */
    private static class TokenResponse {
        private String accessToken;
        private Integer expiresIn;
        private String tokenType;
        
        public String getAccessToken() {
            return accessToken;
        }
        
        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }
        
        public Integer getExpiresIn() {
            return expiresIn;
        }
        
        public void setExpiresIn(Integer expiresIn) {
            this.expiresIn = expiresIn;
        }
        
        public String getTokenType() {
            return tokenType;
        }
        
        public void setTokenType(String tokenType) {
            this.tokenType = tokenType;
        }
    }
}
