# Option 2: Direct Token Exchange

## Overview

**Architecture:**
- Backend exchanges LSCSAD token for camunda-platform token using Keycloak Token Exchange
- Camunda exchanges camunda-platform token for LSCSAD token for callbacks

**Flow:**
1. Frontend authenticates with LSCSAD realm
2. Frontend calls GraphQL mutation via backend
3. Backend validates LSCSAD token
4. Backend exchanges LSCSAD token for camunda-platform token
5. Backend uses exchanged token to call Camunda APIs
6. Camunda exchanges token for LSCSAD token to call backend callbacks

**Pros:**
- ✅ User-level audit trail in Camunda
- ✅ User identity preserved in Camunda operations
- ✅ Native Keycloak feature
- ✅ Better audit trail than service account

**Cons:**
- ❌ Requires Keycloak Token Exchange feature
- ❌ More complex Keycloak configuration
- ❌ Requires users to exist in both realms (or federated identity)

## Implementation

### 1. Token Exchange Service

**File:** `service/option2/TokenExchangeService.java`

```java
package com.example.camunda.glass.service.option2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenExchangeService {

    private final WebClient webClient;
    
    @Value("${keycloak.url}")
    private String keycloakUrl;
    
    public String exchangeToken(String lscsadToken, String targetRealm, String targetClientId) {
        log.debug("Exchanging LSCSAD token for {} realm token", targetRealm);
        
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", 
            keycloakUrl, targetRealm);
        
        Map<String, String> formData = new HashMap<>();
        formData.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        formData.put("subject_token", lscsadToken);
        formData.put("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
        formData.put("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
        formData.put("audience", targetClientId);
        
        TokenResponse response = webClient.post()
            .uri(tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(formData))
            .retrieve()
            .bodyToMono(TokenResponse.class)
            .block();
        
        if (response == null || response.getAccessToken() == null) {
            throw new RuntimeException("Failed to exchange token for realm: " + targetRealm);
        }
        
        return response.getAccessToken();
    }
    
    public String exchangeToLscsad(String camundaToken, String lscsadClientId) {
        log.debug("Exchanging camunda-platform token for LSCSAD token");
        
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", 
            keycloakUrl, "LSCSAD");
        
        Map<String, String> formData = new HashMap<>();
        formData.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        formData.put("subject_token", camundaToken);
        formData.put("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
        formData.put("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
        formData.put("audience", lscsadClientId);
        
        TokenResponse response = webClient.post()
            .uri(tokenUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(formData))
            .retrieve()
            .bodyToMono(TokenResponse.class)
            .block();
        
        if (response == null || response.getAccessToken() == null) {
            throw new RuntimeException("Failed to exchange token for LSCSAD realm");
        }
        
        return response.getAccessToken();
    }
    
    private static class TokenResponse {
        private String accessToken;
        private Integer expiresIn;
        private String tokenType;
        // Getters and setters
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public Integer getExpiresIn() { return expiresIn; }
        public void setExpiresIn(Integer expiresIn) { this.expiresIn = expiresIn; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
    }
}
```

### 2. Camunda Token Exchange Service

**File:** `service/option2/CamundaTokenExchangeService.java`

```java
package com.example.camunda.glass.service.option2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CamundaTokenExchangeService {

    private final TokenExchangeService tokenExchangeService;
    
    @Value("${camunda.keycloak.realm}")
    private String camundaRealm;
    
    @Value("${camunda.keycloak.client-id}")
    private String camundaClientId;
    
    public String getCamundaToken(Jwt lscsadToken) {
        String lscsadTokenString = lscsadToken.getTokenValue();
        return tokenExchangeService.exchangeToken(
            lscsadTokenString, 
            camundaRealm, 
            camundaClientId
        );
    }
}
```

### 3. Deal Process Service

**File:** `service/option2/DealProcessService.java`

Similar to Option 1, but uses token exchange instead of service account. Uses `CamundaRestClientService` from shared components.

```java
package com.example.camunda.glass.service.option2;

import com.example.camunda.glass.dto.DealInput;
import com.example.camunda.glass.dto.DealResponse;
import com.example.camunda.glass.service.CamundaRestClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DealProcessService {

    private final CamundaRestClientService camundaClient;
    private final CamundaTokenExchangeService tokenExchangeService;
    private static final String PROCESS_DEFINITION_KEY = "deal-initiation-process";
    
    public DealResponse createOrUpdateDeal(
            String processInstanceId, 
            DealInput input, 
            Jwt lscsadToken) {
        
        // Exchange LSCSAD token for Camunda token
        String camundaToken = tokenExchangeService.getCamundaToken(lscsadToken);
        String userId = extractUserId(lscsadToken);
        
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            return createDeal(input, userId, camundaToken);
        } else {
            return updateDeal(processInstanceId, input, userId, camundaToken);
        }
    }
    
    // Implementation similar to Option 1, but uses exchanged token
    // See Option 1 for full implementation pattern
    
    private String extractUserId(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isEmpty()) {
            return email;
        }
        String username = jwt.getClaimAsString("preferred_username");
        if (username != null && !username.isEmpty()) {
            return username;
        }
        return jwt.getSubject();
    }
    
    // Other methods similar to Option 1...
}
```

## Configuration

**File:** `application.yml`

```yaml
camunda:
  auth:
    option: option2
  
  rest:
    base-url: http://localhost:8081
  
  keycloak:
    realm: camunda-platform
    client-id: ${CAMUNDA_CLIENT_ID}

keycloak:
  url: http://keycloak:8080
```

## Keycloak Setup

1. **Enable Token Exchange in Keycloak:**
   - Go to Realm Settings → Tokens
   - Enable "Token Exchange" feature

2. **Configure Identity Provider:**
   - In `camunda-platform` realm, add `LSCSAD` as Identity Provider
   - Configure token exchange permissions
   - Grant exchange permissions to backend client

3. **Configure Backend Client:**
   - In `camunda-platform` realm, grant token exchange permissions
   - In `LSCSAD` realm, grant token exchange permissions

## Environment Variables

```bash
CAMUNDA_CLIENT_ID=camunda-client
```

## Usage

Same GraphQL mutation as Option 1. Backend automatically exchanges tokens.
