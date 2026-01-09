# Option 1: Service Account Pattern

## Overview

**Architecture:**
- **user1**: Service account in `camunda-platform` realm for Backend → Camunda communication
- **user2**: Service account in `LSCSAD` realm for Camunda → Backend callbacks

**Flow:**
1. Frontend authenticates with LSCSAD realm
2. Frontend calls GraphQL mutation via backend
3. Backend validates LSCSAD token
4. Backend uses **user1** (camunda-platform service account) to call Camunda APIs
5. Camunda uses **user2** (LSCSAD service account) to call backend callbacks

**Pros:**
- ✅ Centralized control and validation
- ✅ High security (credentials never exposed to frontend)
- ✅ Simple token management
- ✅ Camunda implementation details hidden from frontend

**Cons:**
- ❌ Additional network hop (backend proxy)
- ❌ Service account operations (not user-level audit trail)

## Implementation

### 1. Camunda Authentication Service (user1)

**File:** `service/option1/CamundaAuthenticationService.java`

```java
package com.example.camunda.glass.service.option1;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for authenticating with Camunda 8.8 using service account (user1).
 * Uses client credentials grant to obtain access tokens from camunda-platform realm.
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
    
    @Value("${camunda.keycloak.user1.client-id}")
    private String clientId;
    
    @Value("${camunda.keycloak.user1.client-secret}")
    private String clientSecret;
    
    private AccessToken cachedToken;
    private LocalDateTime tokenExpiry;
    
    public String getAccessToken() {
        if (cachedToken == null || isTokenExpired()) {
            refreshToken();
        }
        return cachedToken.getToken();
    }
    
    private boolean isTokenExpired() {
        if (tokenExpiry == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(tokenExpiry.minusMinutes(1));
    }
    
    private void refreshToken() {
        log.debug("Refreshing Camunda access token using user1 service account");
        
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
            throw new RuntimeException("Failed to obtain Camunda access token for user1");
        }
        
        cachedToken = new AccessToken(response.getAccessToken());
        int expiresIn = response.getExpiresIn() != null ? response.getExpiresIn() : 300;
        tokenExpiry = LocalDateTime.now().plusSeconds(expiresIn);
        
        log.debug("Successfully refreshed Camunda access token for user1, expires at: {}", tokenExpiry);
    }
    
    public void clearCache() {
        cachedToken = null;
        tokenExpiry = null;
    }
    
    private static class AccessToken {
        private final String token;
        AccessToken(String token) { this.token = token; }
        String getToken() { return token; }
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

### 2. LSCSAD Authentication Service (user2)

**File:** `service/option1/LscsadAuthenticationService.java`

```java
package com.example.camunda.glass.service.option1;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for authenticating with LSCSAD realm using service account (user2).
 * Used by Camunda callbacks to authenticate with backend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LscsadAuthenticationService {

    private final WebClient webClient;
    
    @Value("${lscsad.keycloak.url}")
    private String keycloakUrl;
    
    @Value("${lscsad.keycloak.realm}")
    private String realm;
    
    @Value("${lscsad.keycloak.user2.client-id}")
    private String clientId;
    
    @Value("${lscsad.keycloak.user2.client-secret}")
    private String clientSecret;
    
    private AccessToken cachedToken;
    private LocalDateTime tokenExpiry;
    
    public String getAccessToken() {
        if (cachedToken == null || isTokenExpired()) {
            refreshToken();
        }
        return cachedToken.getToken();
    }
    
    private boolean isTokenExpired() {
        if (tokenExpiry == null) {
            return true;
        }
        return LocalDateTime.now().isAfter(tokenExpiry.minusMinutes(1));
    }
    
    private void refreshToken() {
        log.debug("Refreshing LSCSAD access token using user2 service account");
        
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
            throw new RuntimeException("Failed to obtain LSCSAD access token for user2");
        }
        
        cachedToken = new AccessToken(response.getAccessToken());
        int expiresIn = response.getExpiresIn() != null ? response.getExpiresIn() : 300;
        tokenExpiry = LocalDateTime.now().plusSeconds(expiresIn);
        
        log.debug("Successfully refreshed LSCSAD access token for user2, expires at: {}", tokenExpiry);
    }
    
    public void clearCache() {
        cachedToken = null;
        tokenExpiry = null;
    }
    
    private static class AccessToken {
        private final String token;
        AccessToken(String token) { this.token = token; }
        String getToken() { return token; }
    }
    
    private static class TokenResponse {
        private String accessToken;
        private Integer expiresIn;
        private String tokenType;
        // Getters and setters (same as above)
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public Integer getExpiresIn() { return expiresIn; }
        public void setExpiresIn(Integer expiresIn) { this.expiresIn = expiresIn; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
    }
}
```

### 3. Deal Process Service

**File:** `service/option1/DealProcessService.java`

See `SHARED_COMPONENTS.md` for the shared `CamundaRestClientService`. This service uses it:

```java
package com.example.camunda.glass.service.option1;

import com.example.camunda.glass.dto.DealInput;
import com.example.camunda.glass.dto.DealResponse;
import com.example.camunda.glass.service.CamundaRestClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final CamundaAuthenticationService camundaAuth;
    private static final String PROCESS_DEFINITION_KEY = "deal-initiation-process";
    
    public DealResponse createOrUpdateDeal(String processInstanceId, DealInput input, String userId) {
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            return createDeal(input, userId);
        } else {
            return updateDeal(processInstanceId, input, userId);
        }
    }
    
    private DealResponse createDeal(DealInput input, String userId) {
        log.info("Creating new deal process for user: {}", userId);
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("initiatedBy", userId);
        variables.put("initiatedAt", Instant.now().toString());
        variables.put("dealName", input.getDealName());
        variables.put("dealType", input.getDealType());
        variables.put("description", input.getDescription());
        variables.put("value", input.getValue());
        
        if (input.getParties() != null && !input.getParties().isEmpty()) {
            variables.put("parties", input.getParties());
        }
        
        if (input.getContacts() != null && !input.getContacts().isEmpty()) {
            variables.put("contacts", input.getContacts());
        }
        
        String businessKey = generateBusinessKey();
        variables.put("businessKey", businessKey);
        
        String camundaToken = camundaAuth.getAccessToken();
        
        CamundaRestClientService.ProcessInstanceDto processInstance = 
            camundaClient.startProcess(PROCESS_DEFINITION_KEY, variables, businessKey, camundaToken);
        
        List<CamundaRestClientService.TaskDto> activeTasks = 
            camundaClient.getActiveTasks(processInstance.getProcessInstanceKey().toString(), camundaToken);
        
        return DealResponse.builder()
            .id(businessKey)
            .processInstanceId(processInstance.getProcessInstanceKey().toString())
            .dealName(input.getDealName())
            .dealType(input.getDealType())
            .description(input.getDescription())
            .value(input.getValue())
            .status("INITIATED")
            .currentStep(activeTasks.isEmpty() ? "complete" : getStepFromTask(activeTasks.get(0)))
            .parties(input.getParties())
            .contacts(input.getContacts())
            .createdAt(Instant.now().toString())
            .updatedAt(Instant.now().toString())
            .build();
    }
    
    private DealResponse updateDeal(String processInstanceId, DealInput input, String userId) {
        log.info("Updating deal process {} for user: {}", processInstanceId, userId);
        
        validateProcessOwnership(processInstanceId, userId);
        
        String camundaToken = camundaAuth.getAccessToken();
        
        List<CamundaRestClientService.TaskDto> activeTasks = 
            camundaClient.getActiveTasks(processInstanceId, camundaToken);
        
        if (activeTasks.isEmpty()) {
            throw new IllegalStateException("No active task found for process instance: " + processInstanceId);
        }
        
        CamundaRestClientService.TaskDto currentTask = activeTasks.get(0);
        
        Map<String, Object> variables = new HashMap<>();
        variables.put("dealName", input.getDealName());
        variables.put("dealType", input.getDealType());
        variables.put("description", input.getDescription());
        variables.put("value", input.getValue());
        
        if (input.getParties() != null) {
            variables.put("parties", input.getParties());
        }
        
        if (input.getContacts() != null) {
            variables.put("contacts", input.getContacts());
        }
        
        camundaClient.updateProcessVariables(processInstanceId, variables, camundaToken);
        camundaClient.completeTask(Long.parseLong(currentTask.getId()), variables, camundaToken);
        
        List<CamundaRestClientService.TaskDto> nextTasks = 
            camundaClient.getActiveTasks(processInstanceId, camundaToken);
        
        Map<String, Object> processVars = camundaClient.getProcessVariables(processInstanceId, camundaToken);
        
        return DealResponse.builder()
            .id((String) processVars.get("businessKey"))
            .processInstanceId(processInstanceId)
            .dealName(input.getDealName())
            .dealType(input.getDealType())
            .description(input.getDescription())
            .value(input.getValue())
            .status("IN_PROGRESS")
            .currentStep(nextTasks.isEmpty() ? "complete" : getStepFromTask(nextTasks.get(0)))
            .parties(input.getParties())
            .contacts(input.getContacts())
            .createdAt((String) processVars.get("initiatedAt"))
            .updatedAt(Instant.now().toString())
            .build();
    }
    
    private void validateProcessOwnership(String processInstanceId, String userId) {
        String camundaToken = camundaAuth.getAccessToken();
        Map<String, Object> variables = camundaClient.getProcessVariables(processInstanceId, camundaToken);
        String initiatedBy = (String) variables.get("initiatedBy");
        
        if (initiatedBy == null || !initiatedBy.equals(userId)) {
            throw new SecurityException("User " + userId + " is not authorized to access process " + processInstanceId);
        }
    }
    
    private String generateBusinessKey() {
        return "DEAL-" + Instant.now().toEpochMilli();
    }
    
    private String getStepFromTask(CamundaRestClientService.TaskDto task) {
        String formKey = task.getFormKey();
        if (formKey != null) {
            return formKey;
        }
        return task.getName().toLowerCase().replace(" ", "-");
    }
}
```

### 4. GraphQL Resolver Integration

**File:** `graphql/DealProcessDataFetcher.java`

```java
// In DealProcessDataFetcher, inject Option 1 service:
@Autowired
@Qualifier("option1DealProcessService")
private com.example.camunda.glass.service.option1.DealProcessService dealProcessService;

@DgsMutation
public DealResponse deal(
        @InputArgument String processInstanceId,
        @InputArgument DealInput input) {
    
    Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    String userId = extractUserId(jwt);
    
    return dealProcessService.createOrUpdateDeal(processInstanceId, input, userId);
}
```

## Configuration

**File:** `application.yml`

```yaml
camunda:
  auth:
    option: option1
  
  rest:
    base-url: http://localhost:8081
  
  keycloak:
    url: http://keycloak:8080
    realm: camunda-platform
    user1:
      client-id: ${CAMUNDA_USER1_CLIENT_ID}
      client-secret: ${CAMUNDA_USER1_CLIENT_SECRET}

lscsad:
  keycloak:
    url: http://keycloak:8080
    realm: LSCSAD
    user2:
      client-id: ${LSCSAD_USER2_CLIENT_ID}
      client-secret: ${LSCSAD_USER2_CLIENT_SECRET}
```

## Keycloak Setup

1. **Create user1 in camunda-platform realm:**
   - Client ID: `camunda-backend-service`
   - Client Secret: `<generate-secret>`
   - Grant Type: `client_credentials`
   - Roles: Assign appropriate Camunda roles

2. **Create user2 in LSCSAD realm:**
   - Client ID: `camunda-callback-service`
   - Client Secret: `<generate-secret>`
   - Grant Type: `client_credentials`
   - Roles: Assign appropriate backend API roles

## Environment Variables

```bash
CAMUNDA_USER1_CLIENT_ID=camunda-backend-service
CAMUNDA_USER1_CLIENT_SECRET=<secret>
LSCSAD_USER2_CLIENT_ID=camunda-callback-service
LSCSAD_USER2_CLIENT_SECRET=<secret>
```

## Usage

Frontend calls GraphQL mutation:

```graphql
mutation {
  deal(input: {
    dealName: "Acme Corp Deal"
    dealType: "M&A"
    value: 1000000
  }) {
    id
    processInstanceId
    status
  }
}
```

Backend handles authentication and Camunda API calls automatically.
