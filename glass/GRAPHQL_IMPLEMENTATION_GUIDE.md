# GraphQL Implementation Guide: Camunda 8.8 with Netflix DGS

## Overview

This guide provides a complete implementation for integrating Camunda 8.8 with a Spring Boot backend using **GraphQL (Netflix DGS)** and **Keycloak authentication** across two realms (LSCSAD and camunda-platform).

**Key Features:**
- Single GraphQL mutation for deal operations (init/create/update)
- Netflix DGS framework for GraphQL resolvers
- Three authentication options: Service Account Pattern, Direct Token Exchange, and Direct Frontend Access
- Bidirectional authentication (Backend → Camunda and Camunda → Backend)
- Reusable Camunda REST client service for direct frontend access

---

## Architecture

```
┌─────────────┐
│   Angular   │ ──GraphQL Mutation──▶
│   Frontend  │    (Netflix DGS)     │
└─────────────┘                      │
      │                               │
      │                               ▼
      │                      ┌──────────────┐
      │                      │ Spring Boot  │
      │                      │   Backend    │
      │                      │ (Netflix DGS)│
      │                      └──────────────┘
      │                            │
      │            ┌───────────────┴───────────────┐
      │            │                                │
      │  Option 1: Service Account      Option 2: Token Exchange
      │  (user1 → Camunda)              (LSCSAD → camunda-platform)
      │            │                                │
      │            ▼                                ▼
      │    ┌─────────────┐                ┌─────────────┐
      │    │  Camunda 8  │                │  Camunda 8  │
      │    │   Engine    │                │   Engine    │
      │    └─────────────┘                └─────────────┘
      │            │                                │
      │            │ HTTP Connector                │ HTTP Connector
      │            │ (user2 → Backend)            │ (Token Exchange)
      │            │                                │
      │            ▼                                ▼
      │    ┌──────────────┐                ┌──────────────┐
      │    │ Spring Boot  │                │ Spring Boot  │
      │    │   Backend    │                │   Backend    │
      │    │ (Callback)   │                │ (Callback)  │
      │    └──────────────┘                └──────────────┘
      │
      │ Option 3: Direct Frontend Access
      │ (LSCSAD + camunda-platform tokens)
      │            │
      │            ▼
      │    ┌─────────────┐
      │    │  Camunda 8  │
      │    │ REST API    │
      │    │ (Direct)    │
      │    └─────────────┘
      │            │
      │            │ HTTP Connector
      │            │ (camunda-platform token → Backend)
      │            │
      │            ▼
      │    ┌──────────────┐
      │    │ Spring Boot  │
      │    │   Backend    │
      │    │ (Callback)   │
      │    └──────────────┘
```

---

## Authentication Options

### Option 1: Service Account Pattern

**Architecture:**
- **user1**: Service account in `camunda-platform` realm for Backend → Camunda communication
- **user2**: Service account in `LSCSAD` realm for Camunda → Backend callbacks

**Flow:**
1. Frontend authenticates with LSCSAD realm
2. Backend validates LSCSAD token
3. Backend uses **user1** (camunda-platform service account) to call Camunda APIs
4. Camunda uses **user2** (LSCSAD service account) to call backend callbacks

### Option 2: Direct Token Exchange

**Architecture:**
- Backend exchanges LSCSAD token for camunda-platform token using Keycloak Token Exchange
- Camunda exchanges camunda-platform token for LSCSAD token for callbacks

**Flow:**
1. Frontend authenticates with LSCSAD realm
2. Backend validates LSCSAD token
3. Backend exchanges LSCSAD token for camunda-platform token
4. Backend uses exchanged token to call Camunda APIs
5. Camunda exchanges token for LSCSAD token to call backend callbacks

### Option 3: Direct Frontend Access (Reusable Service)

**Architecture:**
- Frontend authenticates to both `LSCSAD` and `camunda-platform` realms
- Frontend calls Camunda REST API directly using camunda-platform token
- Reusable Camunda REST client service that can be used across multiple applications
- Backend still handles callbacks from Camunda

**Flow:**
1. Frontend authenticates with LSCSAD realm (for backend API calls)
2. Frontend authenticates with camunda-platform realm (for Camunda API calls)
3. Frontend uses camunda-platform token to call Camunda REST API directly
4. Camunda uses camunda-platform token to call backend callbacks
5. Backend validates camunda-platform token for callbacks

**Pros:**
- Direct access to Camunda APIs (no backend proxy)
- Reusable service pattern for multiple applications
- Lower latency (fewer network hops)
- Frontend has full control over Camunda operations

**Cons:**
- Frontend must manage two tokens
- Requires CORS configuration on Camunda
- Frontend must handle Camunda authentication complexity
- Less centralized control and validation

**Use Cases:**
- When multiple applications need direct Camunda access
- When performance is critical (low latency requirements)
- When frontend needs real-time process updates
- When building a reusable Camunda integration library

---

## Implementation Structure

All implementation files are located in: `camunda-integration-examples/glass/`

```
glass/
├── GRAPHQL_IMPLEMENTATION_GUIDE.md (this file)
├── graphql/
│   ├── schema.graphqls
│   └── DealProcessDataFetcher.java
├── service/
│   ├── CamundaRestClientService.java (shared - backend)
│   ├── option1/
│   │   ├── CamundaAuthenticationService.java (user1)
│   │   ├── LscsadAuthenticationService.java (user2)
│   │   └── DealProcessService.java
│   ├── option2/
│   │   ├── TokenExchangeService.java
│   │   ├── CamundaTokenExchangeService.java
│   │   └── DealProcessService.java
│   └── option3/
│       ├── CamundaRestClient.ts (reusable frontend service)
│       ├── CamundaAuthService.ts (dual authentication)
│       └── DealProcessService.ts (frontend service)
├── config/
│   ├── WebClientConfig.java
│   └── SecurityConfig.java
├── dto/
│   ├── DealInput.java
│   └── DealResponse.java
└── controller/
    └── DealCallbackController.java
```

---

## GraphQL Schema

**File:** `graphql/schema.graphqls`

```graphql
type Deal {
  id: ID!
  processInstanceId: String!
  dealName: String!
  dealType: String!
  description: String
  value: Float
  status: DealStatus!
  currentStep: String!
  parties: [Party!]
  contacts: [Contact!]
  createdAt: String!
  updatedAt: String!
}

type Party {
  name: String!
  type: String!
  role: String!
  contactEmail: String
}

type Contact {
  firstName: String!
  lastName: String!
  email: String!
  phone: String
  role: String
}

enum DealStatus {
  INITIATED
  IN_PROGRESS
  COMPLETED
  FAILED
}

input DealInput {
  dealName: String!
  dealType: String!
  description: String
  value: Float
  parties: [PartyInput!]
  contacts: [ContactInput!]
}

input PartyInput {
  name: String!
  type: String!
  role: String!
  contactEmail: String
}

input ContactInput {
  firstName: String!
  lastName: String!
  email: String!
  phone: String
  role: String
}

type Mutation {
  """
  Single mutation for init/create/update deal operations.
  If processInstanceId is provided, updates existing deal.
  If not provided, creates new deal process.
  """
  deal(
    processInstanceId: String
    input: DealInput!
  ): Deal!
}

type Query {
  deal(processInstanceId: String!): Deal
  deals(status: DealStatus): [Deal!]!
}
```

---

## Shared Camunda REST Client Service

**File:** `service/CamundaRestClientService.java`

This service is shared by both Option 1 and Option 2. It provides methods to interact with Camunda 8.8 REST API.

```java
package com.example.camunda.glass.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared service for interacting with Camunda 8.8 REST API.
 * Used by both Option 1 (Service Account) and Option 2 (Token Exchange).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CamundaRestClientService {
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    @Value("${camunda.rest.base-url}")
    private String camundaBaseUrl;
    
    /**
     * Start a new process instance.
     * For Option 1: token comes from CamundaAuthenticationService
     * For Option 2: token comes from TokenExchangeService
     */
    public ProcessInstanceDto startProcess(
            String processDefinitionKey, 
            Map<String, Object> variables,
            String businessKey,
            String accessToken) {
        
        log.debug("Starting process instance: processDefinitionKey={}, businessKey={}", 
            processDefinitionKey, businessKey);
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("bpmnProcessId", processDefinitionKey);
        if (businessKey != null) {
            requestBody.put("businessKey", businessKey);
        }
        if (variables != null && !variables.isEmpty()) {
            requestBody.put("variables", convertVariables(variables));
        }
        
        ProcessInstanceDto response = webClient.post()
            .uri(camundaBaseUrl + "/v1/process-instances")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(ProcessInstanceDto.class)
            .block();
        
        log.info("Started process instance: processInstanceKey={}", 
            response != null ? response.getProcessInstanceKey() : "null");
        
        return response;
    }
    
    /**
     * Get active tasks for a process instance.
     */
    public List<TaskDto> getActiveTasks(String processInstanceKey, String accessToken) {
        log.debug("Getting active tasks for process instance: {}", processInstanceKey);
        
        List<TaskDto> tasks = webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path(camundaBaseUrl + "/v1/tasks")
                .queryParam("processInstanceKey", processInstanceKey)
                .build())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .bodyToFlux(TaskDto.class)
            .collectList()
            .block();
        
        log.debug("Found {} active tasks for process instance {}", 
            tasks != null ? tasks.size() : 0, processInstanceKey);
        
        return tasks != null ? tasks : List.of();
    }
    
    /**
     * Complete a task.
     */
    public void completeTask(Long taskKey, Map<String, Object> variables, String accessToken) {
        log.debug("Completing task: taskKey={}", taskKey);
        
        Map<String, Object> requestBody = new HashMap<>();
        if (variables != null && !variables.isEmpty()) {
            requestBody.put("variables", convertVariables(variables));
        }
        
        webClient.post()
            .uri(camundaBaseUrl + "/v1/tasks/" + taskKey + "/complete")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(Void.class)
            .block();
        
        log.info("Completed task: taskKey={}", taskKey);
    }
    
    /**
     * Update process instance variables.
     */
    public void updateProcessVariables(
            String processInstanceKey, 
            Map<String, Object> variables,
            String accessToken) {
        
        log.debug("Updating process variables for instance: {}", processInstanceKey);
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("variables", convertVariables(variables));
        
        webClient.patch()
            .uri(camundaBaseUrl + "/v1/process-instances/" + processInstanceKey + "/variables")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(Void.class)
            .block();
        
        log.debug("Updated process variables for instance: {}", processInstanceKey);
    }
    
    /**
     * Get process instance variables.
     */
    public Map<String, Object> getProcessVariables(String processInstanceKey, String accessToken) {
        log.debug("Getting process variables for instance: {}", processInstanceKey);
        
        Map<String, Object> response = webClient.get()
            .uri(camundaBaseUrl + "/v1/process-instances/" + processInstanceKey + "/variables")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .bodyToMono(Map.class)
            .block();
        
        // Parse variables from Camunda response format
        if (response != null && response.containsKey("variables")) {
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> variablesMap = 
                (Map<String, Map<String, Object>>) response.get("variables");
            
            Map<String, Object> result = new HashMap<>();
            variablesMap.forEach((key, valueMap) -> {
                result.put(key, valueMap.get("value"));
            });
            return result;
        }
        
        return Map.of();
    }
    
    /**
     * Convert variables to Camunda format.
     */
    private Map<String, Map<String, Object>> convertVariables(Map<String, Object> variables) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        
        variables.forEach((key, value) -> {
            Map<String, Object> variableMap = new HashMap<>();
            variableMap.put("value", value);
            
            String type = "String";
            if (value instanceof Number) {
                type = value instanceof Long || value instanceof Integer ? "Long" : "Double";
            } else if (value instanceof Boolean) {
                type = "Boolean";
            } else if (value instanceof Map || value instanceof List) {
                type = "Json";
                try {
                    variableMap.put("value", objectMapper.writeValueAsString(value));
                } catch (Exception e) {
                    log.warn("Failed to serialize variable {} to JSON", key, e);
                    variableMap.put("value", value.toString());
                }
            }
            
            variableMap.put("type", type);
            result.put(key, variableMap);
        });
        
        return result;
    }
    
    // DTOs
    public static class ProcessInstanceDto {
        private Long processInstanceKey;
        private String bpmnProcessId;
        private Long processDefinitionKey;
        private String businessKey;
        private String state;
        
        public Long getProcessInstanceKey() {
            return processInstanceKey;
        }
        
        public void setProcessInstanceKey(Long processInstanceKey) {
            this.processInstanceKey = processInstanceKey;
        }
        
        public String getBpmnProcessId() {
            return bpmnProcessId;
        }
        
        public void setBpmnProcessId(String bpmnProcessId) {
            this.bpmnProcessId = bpmnProcessId;
        }
        
        public Long getProcessDefinitionKey() {
            return processDefinitionKey;
        }
        
        public void setProcessDefinitionKey(Long processDefinitionKey) {
            this.processDefinitionKey = processDefinitionKey;
        }
        
        public String getBusinessKey() {
            return businessKey;
        }
        
        public void setBusinessKey(String businessKey) {
            this.businessKey = businessKey;
        }
        
        public String getState() {
            return state;
        }
        
        public void setState(String state) {
            this.state = state;
        }
    }
    
    public static class TaskDto {
        private Long key;
        private String name;
        private String taskDefinitionId;
        private String processInstanceKey;
        private String assignee;
        private String formKey;
        
        public Long getKey() {
            return key;
        }
        
        public void setKey(Long key) {
            this.key = key;
        }
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getTaskDefinitionId() {
            return taskDefinitionId;
        }
        
        public void setTaskDefinitionId(String taskDefinitionId) {
            this.taskDefinitionId = taskDefinitionId;
        }
        
        public String getProcessInstanceKey() {
            return processInstanceKey;
        }
        
        public void setProcessInstanceKey(String processInstanceKey) {
            this.processInstanceKey = processInstanceKey;
        }
        
        public String getAssignee() {
            return assignee;
        }
        
        public void setAssignee(String assignee) {
            this.assignee = assignee;
        }
        
        public String getFormKey() {
            return formKey;
        }
        
        public void setFormKey(String formKey) {
            this.formKey = formKey;
        }
        
        public Long getId() {
            return key;
        }
    }
}
```

---

## Option 1: Service Account Pattern Implementation

### 1.1 Camunda Authentication Service (user1)

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
import reactor.core.publisher.Mono;

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
    
    /**
     * Get a valid access token for Camunda API calls using user1 service account.
     */
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
        
        AccessToken(String token) {
            this.token = token;
        }
        
        String getToken() {
            return token;
        }
    }
    
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
```

### 1.2 LSCSAD Authentication Service (user2)

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
import reactor.core.publisher.Mono;

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
    
    /**
     * Get a valid access token for LSCSAD realm API calls using user2 service account.
     */
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
        
        AccessToken(String token) {
            this.token = token;
        }
        
        String getToken() {
            return token;
        }
    }
    
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
```

### 1.3 Deal Process Service (Option 1)

**File:** `service/option1/DealProcessService.java`

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

/**
 * Service for managing deal processes using Service Account Pattern (Option 1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DealProcessService {

    private final CamundaRestClientService camundaClient;
    private final CamundaAuthenticationService camundaAuth;
    private static final String PROCESS_DEFINITION_KEY = "deal-initiation-process";
    
    /**
     * Create or update deal process.
     * If processInstanceId is null, creates new process.
     * If processInstanceId is provided, updates existing process.
     */
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
        
        // Validate process ownership
        validateProcessOwnership(processInstanceId, userId);
        
        String camundaToken = camundaAuth.getAccessToken();
        
        // Get current active task
        List<CamundaRestClientService.TaskDto> activeTasks = 
            camundaClient.getActiveTasks(processInstanceId, camundaToken);
        
        if (activeTasks.isEmpty()) {
            throw new IllegalStateException("No active task found for process instance: " + processInstanceId);
        }
        
        CamundaRestClientService.TaskDto currentTask = activeTasks.get(0);
        
        // Update process variables
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
        
        // Complete task to advance process
        camundaClient.completeTask(Long.parseLong(currentTask.getId()), variables, camundaToken);
        
        // Get next active task
        List<CamundaRestClientService.TaskDto> nextTasks = 
            camundaClient.getActiveTasks(processInstanceId, camundaToken);
        
        // Get current process variables
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

---

## Option 2: Direct Token Exchange Implementation

### 2.1 Token Exchange Service

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
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for exchanging tokens between Keycloak realms using Token Exchange feature.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenExchangeService {

    private final WebClient webClient;
    
    @Value("${keycloak.url}")
    private String keycloakUrl;
    
    /**
     * Exchange LSCSAD token for camunda-platform token.
     * 
     * @param lscsadToken The LSCSAD realm access token
     * @param targetRealm The target realm (camunda-platform)
     * @param targetClientId The target client ID
     * @return The exchanged access token for target realm
     */
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
        
        log.debug("Successfully exchanged token for realm: {}", targetRealm);
        return response.getAccessToken();
    }
    
    /**
     * Exchange camunda-platform token for LSCSAD token (for callbacks).
     */
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
        
        log.debug("Successfully exchanged token for LSCSAD realm");
        return response.getAccessToken();
    }
    
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
```

### 2.2 Camunda Token Exchange Service

**File:** `service/option2/CamundaTokenExchangeService.java`

```java
package com.example.camunda.glass.service.option2;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Service for managing Camunda authentication using Token Exchange (Option 2).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CamundaTokenExchangeService {

    private final TokenExchangeService tokenExchangeService;
    
    @Value("${camunda.keycloak.realm}")
    private String camundaRealm;
    
    @Value("${camunda.keycloak.client-id}")
    private String camundaClientId;
    
    /**
     * Get Camunda access token by exchanging LSCSAD token.
     */
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

### 2.3 Deal Process Service (Option 2)

**File:** `service/option2/DealProcessService.java`

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

/**
 * Service for managing deal processes using Token Exchange (Option 2).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DealProcessService {

    private final CamundaRestClientService camundaClient;
    private final CamundaTokenExchangeService tokenExchangeService;
    private static final String PROCESS_DEFINITION_KEY = "deal-initiation-process";
    
    /**
     * Create or update deal process.
     * Uses token exchange to get Camunda token from LSCSAD token.
     */
    public DealResponse createOrUpdateDeal(
            String processInstanceId, 
            DealInput input, 
            Jwt lscsadToken) {
        
        // Exchange LSCSAD token for Camunda token
        String camundaToken = tokenExchangeService.getCamundaToken(lscsadToken);
        
        // Extract user ID from token
        String userId = extractUserId(lscsadToken);
        
        if (processInstanceId == null || processInstanceId.isEmpty()) {
            return createDeal(input, userId, camundaToken);
        } else {
            return updateDeal(processInstanceId, input, userId, camundaToken);
        }
    }
    
    private DealResponse createDeal(DealInput input, String userId, String camundaToken) {
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
    
    private DealResponse updateDeal(
            String processInstanceId, 
            DealInput input, 
            String userId, 
            String camundaToken) {
        
        log.info("Updating deal process {} for user: {}", processInstanceId, userId);
        
        // Validate process ownership
        validateProcessOwnership(processInstanceId, userId, camundaToken);
        
        // Get current active task
        List<CamundaRestClientService.TaskDto> activeTasks = 
            camundaClient.getActiveTasks(processInstanceId, camundaToken);
        
        if (activeTasks.isEmpty()) {
            throw new IllegalStateException("No active task found for process instance: " + processInstanceId);
        }
        
        CamundaRestClientService.TaskDto currentTask = activeTasks.get(0);
        
        // Update process variables
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
        
        // Complete task to advance process
        camundaClient.completeTask(Long.parseLong(currentTask.getId()), variables, camundaToken);
        
        // Get next active task
        List<CamundaRestClientService.TaskDto> nextTasks = 
            camundaClient.getActiveTasks(processInstanceId, camundaToken);
        
        // Get current process variables
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
    
    private void validateProcessOwnership(String processInstanceId, String userId, String camundaToken) {
        Map<String, Object> variables = camundaClient.getProcessVariables(processInstanceId, camundaToken);
        String initiatedBy = (String) variables.get("initiatedBy");
        
        if (initiatedBy == null || !initiatedBy.equals(userId)) {
            throw new SecurityException("User " + userId + " is not authorized to access process " + processInstanceId);
        }
    }
    
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

---

## GraphQL Resolver (Netflix DGS)

**File:** `graphql/DealProcessDataFetcher.java`

```java
package com.example.camunda.glass.graphql;

import com.example.camunda.glass.dto.DealInput;
import com.example.camunda.glass.dto.DealResponse;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * GraphQL resolver for deal process operations using Netflix DGS.
 * Supports both Option 1 (Service Account) and Option 2 (Token Exchange).
 * 
 * Note: Configure only one option's service as a bean based on camunda.auth.option property.
 */
@Slf4j
@DgsComponent
@RequiredArgsConstructor
public class DealProcessDataFetcher {

    @Value("${camunda.auth.option:option1}")
    private String authOption;
    
    // Inject the appropriate service based on configuration
    // Option 1: Service Account Pattern
    private final com.example.camunda.glass.service.option1.DealProcessService option1Service;
    
    // Option 2: Token Exchange (optional, only if using option2)
    // Uncomment and configure if using Option 2:
    // private final com.example.camunda.glass.service.option2.DealProcessService option2Service;
    
    /**
     * Single mutation for init/create/update deal operations.
     * If processInstanceId is provided, updates existing deal.
     * If not provided, creates new deal process.
     */
    @DgsMutation
    public DealResponse deal(
            @InputArgument String processInstanceId,
            @InputArgument DealInput input) {
        
        log.info("Processing deal mutation: processInstanceId={}, dealName={}, authOption={}", 
            processInstanceId, input.getDealName(), authOption);
        
        // Extract user from security context
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String userId = extractUserId(jwt);
        
        if ("option1".equals(authOption)) {
            return option1Service.createOrUpdateDeal(processInstanceId, input, userId);
        } else if ("option2".equals(authOption)) {
            // For Option 2, uncomment this line and inject option2Service:
            // return option2Service.createOrUpdateDeal(processInstanceId, input, jwt);
            throw new UnsupportedOperationException("Option 2 (Token Exchange) not configured. Please configure option2Service bean.");
        } else {
            throw new IllegalArgumentException("Invalid camunda.auth.option: " + authOption + ". Must be 'option1' or 'option2'");
        }
    }
    
    @DgsQuery
    public DealResponse deal(@InputArgument String processInstanceId) {
        // Implementation for querying deal
        // This would use CamundaRestClientService to get process instance
        // and map to DealResponse
        throw new UnsupportedOperationException("Query implementation pending");
    }
    
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
}
```

---

## Configuration Files

### Application Configuration

**File:** `config/application.yml`

```yaml
spring:
  application:
    name: camunda-graphql-service
  
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://keycloak:8080/realms/LSCSAD
          jwk-set-uri: http://keycloak:8080/realms/LSCSAD/protocol/openid-connect/certs

# Camunda authentication option: option1 (Service Account) or option2 (Token Exchange)
camunda:
  auth:
    option: option1  # Change to option2 for token exchange
  
  # Camunda configuration
  rest:
    base-url: http://localhost:8081
  
  # Option 1: Service Account (user1 for Camunda)
  keycloak:
    url: http://keycloak:8080
    realm: camunda-platform
    user1:
      client-id: ${CAMUNDA_USER1_CLIENT_ID}
      client-secret: ${CAMUNDA_USER1_CLIENT_SECRET}
  
  # Option 2: Token Exchange
  keycloak:
    client-id: ${CAMUNDA_CLIENT_ID}

# LSCSAD configuration (user2 for callbacks - Option 1)
lscsad:
  keycloak:
    url: http://keycloak:8080
    realm: LSCSAD
    user2:
      client-id: ${LSCSAD_USER2_CLIENT_ID}
      client-secret: ${LSCSAD_USER2_CLIENT_SECRET}

# Keycloak configuration (for Token Exchange - Option 2)
keycloak:
  url: http://keycloak:8080

# Process definition
deal-process:
  process-definition-key: deal-initiation-process
```

### WebClient Configuration

**File:** `config/WebClientConfig.java`

```java
package com.example.camunda.glass.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {
    
    @Bean
    public WebClient webClient() {
        return WebClient.builder()
            .build();
    }
}
```

### Security Configuration

**File:** `config/SecurityConfig.java`

```java
package com.example.camunda.glass.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/graphql").authenticated()
                .requestMatchers("/graphiql").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {})
            );
        
        return http.build();
    }
}
```

---

## Callback Controller (For Camunda → Backend)

**File:** `controller/DealCallbackController.java`

```java
package com.example.camunda.glass.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for handling Camunda callbacks.
 * Supports both Option 1 (Service Account user2) and Option 2 (Token Exchange).
 */
@Slf4j
@RestController
@RequestMapping("/api/deals")
@RequiredArgsConstructor
public class DealCallbackController {
    
    @Value("${camunda.auth.option:option1}")
    private String authOption;
    
    /**
     * Endpoint for Camunda to call after process completion.
     * Authenticated using LSCSAD realm (user2 for Option 1, or exchanged token for Option 2).
     */
    @PostMapping("/{businessKey}/status-update")
    public ResponseEntity<Map<String, String>> updateDealStatus(
            @PathVariable String businessKey,
            @RequestBody Map<String, Object> callbackData) {
        
        log.info("Received callback for deal: {}", businessKey);
        
        // Extract authenticated user (for Option 1, this is user2 service account)
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        String authenticatedUser = jwt.getClaimAsString("preferred_username");
        
        log.info("Callback authenticated as: {}", authenticatedUser);
        
        // Extract process data
        String processInstanceId = (String) callbackData.get("processInstanceId");
        String status = (String) callbackData.get("status");
        
        // Update deal status in database
        // TODO: Implement database update logic
        
        log.info("Updated deal {} status to: {}", businessKey, status);
        
        return ResponseEntity.ok(Map.of(
            "success", "true",
            "businessKey", businessKey,
            "status", status
        ));
    }
}
```

---

## DTOs

### Deal Input DTO

**File:** `dto/DealInput.java`

```java
package com.example.camunda.glass.dto;

import lombok.Data;
import java.util.List;

@Data
public class DealInput {
    private String dealName;
    private String dealType;
    private String description;
    private Double value;
    private List<PartyInput> parties;
    private List<ContactInput> contacts;
}

@Data
class PartyInput {
    private String name;
    private String type;
    private String role;
    private String contactEmail;
}

@Data
class ContactInput {
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String role;
}
```

### Deal Response DTO

**File:** `dto/DealResponse.java`

```java
package com.example.camunda.glass.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class DealResponse {
    private String id;
    private String processInstanceId;
    private String dealName;
    private String dealType;
    private String description;
    private Double value;
    private String status;
    private String currentStep;
    private List<Party> parties;
    private List<Contact> contacts;
    private String createdAt;
    private String updatedAt;
}

@Data
class Party {
    private String name;
    private String type;
    private String role;
    private String contactEmail;
}

@Data
class Contact {
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String role;
}
```

---

## Option 3: Direct Frontend Access Implementation

### 3.1 Camunda Authentication Service (Frontend)

**File:** `service/option3/CamundaAuthService.ts`

This service handles dual authentication for frontend applications - authenticating to both LSCSAD and camunda-platform realms.

```typescript
import Keycloak from 'keycloak-js';

export interface KeycloakConfig {
  url: string;
  realm: string;
  clientId: string;
}

export interface DualAuthTokens {
  lscsadToken: string;
  camundaToken: string;
  lscsadRefreshToken?: string;
  camundaRefreshToken?: string;
}

/**
 * Service for managing dual authentication to LSCSAD and camunda-platform realms.
 * Reusable across multiple frontend applications.
 */
export class CamundaAuthService {
  private lscsadKeycloak: Keycloak.KeycloakInstance;
  private camundaKeycloak: Keycloak.KeycloakInstance;
  
  private lscsadConfig: KeycloakConfig;
  private camundaConfig: KeycloakConfig;
  
  constructor(
    lscsadConfig: KeycloakConfig,
    camundaConfig: KeycloakConfig
  ) {
    this.lscsadConfig = lscsadConfig;
    this.camundaConfig = camundaConfig;
    
    this.lscsadKeycloak = new Keycloak({
      url: lscsadConfig.url,
      realm: lscsadConfig.realm,
      clientId: lscsadConfig.clientId
    });
    
    this.camundaKeycloak = new Keycloak({
      url: camundaConfig.url,
      realm: camundaConfig.realm,
      clientId: camundaConfig.clientId
    });
  }
  
  /**
   * Initialize both Keycloak instances.
   */
  async init(): Promise<boolean> {
    try {
      const [lscsadInit, camundaInit] = await Promise.all([
        this.lscsadKeycloak.init({
          onLoad: 'check-sso',
          checkLoginIframe: false
        }),
        this.camundaKeycloak.init({
          onLoad: 'check-sso',
          checkLoginIframe: false
        })
      ]);
      
      return lscsadInit && camundaInit;
    } catch (error) {
      console.error('Failed to initialize Keycloak instances:', error);
      return false;
    }
  }
  
  /**
   * Login to both realms.
   */
  async login(): Promise<boolean> {
    try {
      const [lscsadLogin, camundaLogin] = await Promise.all([
        this.lscsadKeycloak.login(),
        this.camundaKeycloak.login()
      ]);
      
      return lscsadLogin && camundaLogin;
    } catch (error) {
      console.error('Failed to login:', error);
      return false;
    }
  }
  
  /**
   * Get tokens from both realms.
   */
  getTokens(): DualAuthTokens | null {
    const lscsadToken = this.lscsadKeycloak.token;
    const camundaToken = this.camundaKeycloak.token;
    
    if (!lscsadToken || !camundaToken) {
      return null;
    }
    
    return {
      lscsadToken,
      camundaToken,
      lscsadRefreshToken: this.lscsadKeycloak.refreshToken,
      camundaRefreshToken: this.camundaKeycloak.refreshToken
    };
  }
  
  /**
   * Get LSCSAD token (for backend API calls).
   */
  getLscsadToken(): string | undefined {
    return this.lscsadKeycloak.token;
  }
  
  /**
   * Get Camunda token (for Camunda API calls).
   */
  getCamundaToken(): string | undefined {
    return this.camundaKeycloak.token;
  }
  
  /**
   * Refresh tokens for both realms.
   */
  async refreshTokens(): Promise<boolean> {
    try {
      const [lscsadRefresh, camundaRefresh] = await Promise.all([
        this.lscsadKeycloak.updateToken(30),
        this.camundaKeycloak.updateToken(30)
      ]);
      
      return lscsadRefresh && camundaRefresh;
    } catch (error) {
      console.error('Failed to refresh tokens:', error);
      return false;
    }
  }
  
  /**
   * Logout from both realms.
   */
  async logout(): Promise<void> {
    await Promise.all([
      this.lscsadKeycloak.logout(),
      this.camundaKeycloak.logout()
    ]);
  }
  
  /**
   * Check if user is authenticated in both realms.
   */
  isAuthenticated(): boolean {
    return this.lscsadKeycloak.authenticated === true && 
           this.camundaKeycloak.authenticated === true;
  }
  
  /**
   * Setup token refresh interval.
   */
  setupTokenRefresh(intervalSeconds: number = 60): void {
    setInterval(async () => {
      if (this.isAuthenticated()) {
        await this.refreshTokens();
      }
    }, intervalSeconds * 1000);
  }
}
```

### 3.2 Reusable Camunda REST Client Service (Frontend)

**File:** `service/option3/CamundaRestClient.ts`

This is a reusable service that can be used across multiple frontend applications to interact with Camunda 8.8 REST API.

```typescript
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { Injectable } from '@angular/core';

export interface ProcessInstanceRequest {
  bpmnProcessId: string;
  businessKey?: string;
  variables?: Record<string, ProcessVariable>;
}

export interface ProcessVariable {
  value: any;
  type: 'String' | 'Long' | 'Double' | 'Boolean' | 'Json';
}

export interface ProcessInstance {
  processInstanceKey: string;
  bpmnProcessId: string;
  processDefinitionKey: string;
  businessKey?: string;
  state: string;
}

export interface Task {
  key: string;
  name: string;
  taskDefinitionId: string;
  processInstanceKey: string;
  assignee?: string;
  formKey?: string;
}

export interface CompleteTaskRequest {
  variables?: Record<string, ProcessVariable>;
}

/**
 * Reusable Camunda 8.8 REST API client service.
 * Can be used across multiple frontend applications.
 * 
 * Usage:
 * ```typescript
 * const client = new CamundaRestClient('http://localhost:8081', () => camundaToken);
 * const process = await client.startProcess({ bpmnProcessId: 'my-process' });
 * ```
 */
@Injectable({
  providedIn: 'root'
})
export class CamundaRestClient {
  
  constructor(
    private http: HttpClient,
    private baseUrl: string,
    private getToken: () => string | undefined
  ) {}
  
  /**
   * Create a new instance of CamundaRestClient.
   * Factory method for easier instantiation.
   */
  static create(
    http: HttpClient,
    baseUrl: string,
    getToken: () => string | undefined
  ): CamundaRestClient {
    return new CamundaRestClient(http, baseUrl, getToken);
  }
  
  /**
   * Get authorization headers with Camunda token.
   */
  private getHeaders(): HttpHeaders {
    const token = this.getToken();
    if (!token) {
      throw new Error('Camunda token not available');
    }
    
    return new HttpHeaders({
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    });
  }
  
  /**
   * Start a new process instance.
   */
  startProcess(request: ProcessInstanceRequest): Observable<ProcessInstance> {
    const body: any = {
      bpmnProcessId: request.bpmnProcessId
    };
    
    if (request.businessKey) {
      body.businessKey = request.businessKey;
    }
    
    if (request.variables) {
      body.variables = request.variables;
    }
    
    return this.http.post<ProcessInstance>(
      `${this.baseUrl}/v1/process-instances`,
      body,
      { headers: this.getHeaders() }
    ).pipe(
      catchError(this.handleError)
    );
  }
  
  /**
   * Get process instance by key.
   */
  getProcessInstance(processInstanceKey: string): Observable<ProcessInstance> {
    return this.http.get<ProcessInstance>(
      `${this.baseUrl}/v1/process-instances/${processInstanceKey}`,
      { headers: this.getHeaders() }
    ).pipe(
      catchError(this.handleError)
    );
  }
  
  /**
   * Get active tasks for a process instance.
   */
  getActiveTasks(processInstanceKey: string): Observable<Task[]> {
    const params = new HttpParams().set('processInstanceKey', processInstanceKey);
    
    return this.http.get<Task[]>(
      `${this.baseUrl}/v1/tasks`,
      { 
        headers: this.getHeaders(),
        params
      }
    ).pipe(
      catchError(this.handleError)
    );
  }
  
  /**
   * Complete a task.
   */
  completeTask(taskKey: string, request?: CompleteTaskRequest): Observable<void> {
    const body: any = {};
    
    if (request?.variables) {
      body.variables = request.variables;
    }
    
    return this.http.post<void>(
      `${this.baseUrl}/v1/tasks/${taskKey}/complete`,
      body,
      { headers: this.getHeaders() }
    ).pipe(
      catchError(this.handleError)
    );
  }
  
  /**
   * Update process instance variables.
   */
  updateProcessVariables(
    processInstanceKey: string,
    variables: Record<string, ProcessVariable>
  ): Observable<void> {
    const body = {
      variables
    };
    
    return this.http.patch<void>(
      `${this.baseUrl}/v1/process-instances/${processInstanceKey}/variables`,
      body,
      { headers: this.getHeaders() }
    ).pipe(
      catchError(this.handleError)
    );
  }
  
  /**
   * Get process instance variables.
   */
  getProcessVariables(processInstanceKey: string): Observable<Record<string, any>> {
    return this.http.get<{ variables: Record<string, { value: any }> }>(
      `${this.baseUrl}/v1/process-instances/${processInstanceKey}/variables`,
      { headers: this.getHeaders() }
    ).pipe(
      map(response => {
        const result: Record<string, any> = {};
        Object.entries(response.variables || {}).forEach(([key, variable]) => {
          result[key] = variable.value;
        });
        return result;
      }),
      catchError(this.handleError)
    );
  }
  
  /**
   * Delete a process instance.
   */
  deleteProcessInstance(processInstanceKey: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/v1/process-instances/${processInstanceKey}`,
      { headers: this.getHeaders() }
    ).pipe(
      catchError(this.handleError)
    );
  }
  
  /**
   * Handle HTTP errors.
   */
  private handleError(error: any): Observable<never> {
    let errorMessage = 'An error occurred';
    
    if (error.error instanceof ErrorEvent) {
      // Client-side error
      errorMessage = `Error: ${error.error.message}`;
    } else {
      // Server-side error
      errorMessage = `Error Code: ${error.status}\nMessage: ${error.message}`;
      if (error.error?.message) {
        errorMessage += `\nDetails: ${error.error.message}`;
      }
    }
    
    console.error(errorMessage);
    return throwError(() => new Error(errorMessage));
  }
}
```

### 3.3 Deal Process Service (Frontend)

**File:** `service/option3/DealProcessService.ts`

Frontend service that uses the reusable Camunda REST client to manage deal processes.

```typescript
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';
import { CamundaRestClient, ProcessInstanceRequest, ProcessVariable } from './CamundaRestClient';
import { CamundaAuthService } from './CamundaAuthService';

export interface DealInput {
  dealName: string;
  dealType: string;
  description?: string;
  value?: number;
  parties?: PartyInput[];
  contacts?: ContactInput[];
}

export interface PartyInput {
  name: string;
  type: string;
  role: string;
  contactEmail?: string;
}

export interface ContactInput {
  firstName: string;
  lastName: string;
  email: string;
  phone?: string;
  role?: string;
}

export interface DealResponse {
  id: string;
  processInstanceId: string;
  dealName: string;
  dealType: string;
  description?: string;
  value?: number;
  status: string;
  currentStep: string;
  parties?: PartyInput[];
  contacts?: ContactInput[];
  createdAt: string;
  updatedAt: string;
}

/**
 * Frontend service for managing deal processes using direct Camunda REST API access.
 */
@Injectable({
  providedIn: 'root'
})
export class DealProcessService {
  
  private camundaClient: CamundaRestClient;
  private static readonly PROCESS_DEFINITION_KEY = 'deal-initiation-process';
  
  constructor(
    private http: any, // HttpClient injected via factory
    private authService: CamundaAuthService,
    private camundaBaseUrl: string
  ) {
    // Initialize reusable Camunda REST client
    this.camundaClient = CamundaRestClient.create(
      http,
      camundaBaseUrl,
      () => authService.getCamundaToken()
    );
  }
  
  /**
   * Create or update deal process.
   * If processInstanceId is provided, updates existing deal.
   * If not provided, creates new deal process.
   */
  createOrUpdateDeal(
    processInstanceId: string | null,
    input: DealInput
  ): Observable<DealResponse> {
    if (!processInstanceId) {
      return this.createDeal(input);
    } else {
      return this.updateDeal(processInstanceId, input);
    }
  }
  
  /**
   * Create a new deal process.
   */
  private createDeal(input: DealInput): Observable<DealResponse> {
    const businessKey = this.generateBusinessKey();
    
    const variables: Record<string, ProcessVariable> = {
      initiatedBy: {
        value: this.getUserId(),
        type: 'String'
      },
      initiatedAt: {
        value: new Date().toISOString(),
        type: 'String'
      },
      dealName: {
        value: input.dealName,
        type: 'String'
      },
      dealType: {
        value: input.dealType,
        type: 'String'
      },
      businessKey: {
        value: businessKey,
        type: 'String'
      }
    };
    
    if (input.description) {
      variables.description = {
        value: input.description,
        type: 'String'
      };
    }
    
    if (input.value !== undefined) {
      variables.value = {
        value: input.value,
        type: 'Double'
      };
    }
    
    if (input.parties && input.parties.length > 0) {
      variables.parties = {
        value: JSON.stringify(input.parties),
        type: 'Json'
      };
    }
    
    if (input.contacts && input.contacts.length > 0) {
      variables.contacts = {
        value: JSON.stringify(input.contacts),
        type: 'Json'
      };
    }
    
    const request: ProcessInstanceRequest = {
      bpmnProcessId: DealProcessService.PROCESS_DEFINITION_KEY,
      businessKey,
      variables
    };
    
    return this.camundaClient.startProcess(request).pipe(
      switchMap(processInstance => 
        this.camundaClient.getActiveTasks(processInstance.processInstanceKey).pipe(
          map(tasks => ({
            processInstance,
            tasks
          }))
        )
      ),
      map(({ processInstance, tasks }) => ({
        id: businessKey,
        processInstanceId: processInstance.processInstanceKey,
        dealName: input.dealName,
        dealType: input.dealType,
        description: input.description,
        value: input.value,
        status: 'INITIATED',
        currentStep: tasks.length > 0 ? this.getStepFromTask(tasks[0]) : 'complete',
        parties: input.parties,
        contacts: input.contacts,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      }))
    );
  }
  
  /**
   * Update an existing deal process.
   */
  private updateDeal(processInstanceId: string, input: DealInput): Observable<DealResponse> {
    return this.camundaClient.getActiveTasks(processInstanceId).pipe(
      switchMap(tasks => {
        if (tasks.length === 0) {
          throw new Error('No active task found for process instance: ' + processInstanceId);
        }
        
        const currentTask = tasks[0];
        
        // Update process variables
        const variables: Record<string, ProcessVariable> = {
          dealName: {
            value: input.dealName,
            type: 'String'
          },
          dealType: {
            value: input.dealType,
            type: 'String'
          }
        };
        
        if (input.description) {
          variables.description = {
            value: input.description,
            type: 'String'
          };
        }
        
        if (input.value !== undefined) {
          variables.value = {
            value: input.value,
            type: 'Double'
          };
        }
        
        if (input.parties) {
          variables.parties = {
            value: JSON.stringify(input.parties),
            type: 'Json'
          };
        }
        
        if (input.contacts) {
          variables.contacts = {
            value: JSON.stringify(input.contacts),
            type: 'Json'
          };
        }
        
        return this.camundaClient.updateProcessVariables(processInstanceId, variables).pipe(
          switchMap(() => 
            this.camundaClient.completeTask(currentTask.key, { variables })
          ),
          switchMap(() => 
            this.camundaClient.getActiveTasks(processInstanceId)
          ),
          switchMap(nextTasks => 
            this.camundaClient.getProcessVariables(processInstanceId).pipe(
              map(processVars => ({
                nextTasks,
                processVars
              }))
            )
          ),
          map(({ nextTasks, processVars }) => ({
            id: processVars.businessKey || processInstanceId,
            processInstanceId,
            dealName: input.dealName,
            dealType: input.dealType,
            description: input.description,
            value: input.value,
            status: 'IN_PROGRESS',
            currentStep: nextTasks.length > 0 ? this.getStepFromTask(nextTasks[0]) : 'complete',
            parties: input.parties,
            contacts: input.contacts,
            createdAt: processVars.initiatedAt || new Date().toISOString(),
            updatedAt: new Date().toISOString()
          }))
        );
      })
    );
  }
  
  /**
   * Get deal process by process instance ID.
   */
  getDeal(processInstanceId: string): Observable<DealResponse> {
    return this.camundaClient.getProcessInstance(processInstanceId).pipe(
      switchMap(processInstance => 
        this.camundaClient.getProcessVariables(processInstanceId).pipe(
          map(variables => ({
            processInstance,
            variables
          }))
        )
      ),
      switchMap(({ processInstance, variables }) => 
        this.camundaClient.getActiveTasks(processInstanceId).pipe(
          map(tasks => ({
            processInstance,
            variables,
            tasks
          }))
        )
      ),
      map(({ processInstance, variables, tasks }) => {
        const parties = variables.parties ? JSON.parse(variables.parties) : undefined;
        const contacts = variables.contacts ? JSON.parse(variables.contacts) : undefined;
        
        return {
          id: variables.businessKey || processInstance.processInstanceKey,
          processInstanceId: processInstance.processInstanceKey,
          dealName: variables.dealName,
          dealType: variables.dealType,
          description: variables.description,
          value: variables.value,
          status: processInstance.state === 'COMPLETED' ? 'COMPLETED' : 'IN_PROGRESS',
          currentStep: tasks.length > 0 ? this.getStepFromTask(tasks[0]) : 'complete',
          parties,
          contacts,
          createdAt: variables.initiatedAt || new Date().toISOString(),
          updatedAt: new Date().toISOString()
        };
      })
    );
  }
  
  private generateBusinessKey(): string {
    return `DEAL-${Date.now()}`;
  }
  
  private getUserId(): string {
    // Extract user ID from LSCSAD token
    // This would typically come from the auth service
    const token = this.authService.getLscsadToken();
    if (!token) {
      throw new Error('LSCSAD token not available');
    }
    
    // Decode JWT token to get user ID
    // In production, use a proper JWT library
    try {
      const payload = JSON.parse(atob(token.split('.')[1]));
      return payload.email || payload.preferred_username || payload.sub;
    } catch (error) {
      throw new Error('Failed to extract user ID from token');
    }
  }
  
  private getStepFromTask(task: any): string {
    return task.formKey || task.name.toLowerCase().replace(/\s+/g, '-');
  }
}
```

### 3.4 Angular Module Configuration

**File:** `service/option3/camunda-client.module.ts`

Module configuration for using the reusable Camunda client in Angular applications.

```typescript
import { NgModule } from '@angular/core';
import { HttpClientModule } from '@angular/common/http';
import { CamundaRestClient } from './CamundaRestClient';
import { CamundaAuthService } from './CamundaAuthService';
import { DealProcessService } from './DealProcessService';

@NgModule({
  imports: [HttpClientModule],
  providers: [
    {
      provide: CamundaAuthService,
      useFactory: () => {
        return new CamundaAuthService(
          {
            url: 'http://keycloak:8080',
            realm: 'LSCSAD',
            clientId: 'angular-app'
          },
          {
            url: 'http://keycloak:8080',
            realm: 'camunda-platform',
            clientId: 'camunda-frontend-client'
          }
        );
      }
    },
    {
      provide: DealProcessService,
      useFactory: (http: HttpClient, authService: CamundaAuthService) => {
        return new DealProcessService(
          http,
          authService,
          'http://localhost:8081' // Camunda REST API base URL
        );
      },
      deps: [HttpClient, CamundaAuthService]
    }
  ]
})
export class CamundaClientModule {
  static forRoot(config: {
    lscsadRealm: string;
    camundaRealm: string;
    camundaBaseUrl: string;
  }) {
    return {
      ngModule: CamundaClientModule,
      providers: [
        {
          provide: CamundaAuthService,
          useFactory: () => {
            return new CamundaAuthService(
              {
                url: 'http://keycloak:8080',
                realm: config.lscsadRealm,
                clientId: 'angular-app'
              },
              {
                url: 'http://keycloak:8080',
                realm: config.camundaRealm,
                clientId: 'camunda-frontend-client'
              }
            );
          }
        }
      ]
    };
  }
}
```

### 3.5 Component Usage Example

**File:** `service/option3/deal.component.ts.example`

Example Angular component using Option 3.

```typescript
import { Component, OnInit } from '@angular/core';
import { CamundaAuthService } from './CamundaAuthService';
import { DealProcessService, DealInput } from './DealProcessService';

@Component({
  selector: 'app-deal',
  template: `
    <div *ngIf="!authenticated">
      <button (click)="login()">Login</button>
    </div>
    
    <div *ngIf="authenticated">
      <form [formGroup]="dealForm" (ngSubmit)="submitDeal()">
        <input formControlName="dealName" placeholder="Deal Name">
        <input formControlName="dealType" placeholder="Deal Type">
        <button type="submit">Create/Update Deal</button>
      </form>
      
      <div *ngIf="deal">
        <h3>{{ deal.dealName }}</h3>
        <p>Status: {{ deal.status }}</p>
        <p>Current Step: {{ deal.currentStep }}</p>
      </div>
    </div>
  `
})
export class DealComponent implements OnInit {
  authenticated = false;
  deal: any = null;
  dealForm: FormGroup;
  
  constructor(
    private authService: CamundaAuthService,
    private dealService: DealProcessService,
    private fb: FormBuilder
  ) {
    this.dealForm = this.fb.group({
      dealName: ['', Validators.required],
      dealType: ['', Validators.required]
    });
  }
  
  async ngOnInit() {
    await this.authService.init();
    this.authenticated = this.authService.isAuthenticated();
    
    if (this.authenticated) {
      // Setup token refresh
      this.authService.setupTokenRefresh(60);
    }
  }
  
  async login() {
    const success = await this.authService.login();
    if (success) {
      this.authenticated = true;
      this.authService.setupTokenRefresh(60);
    }
  }
  
  submitDeal() {
    if (this.dealForm.valid) {
      const input: DealInput = this.dealForm.value;
      
      this.dealService.createOrUpdateDeal(null, input).subscribe({
        next: (deal) => {
          this.deal = deal;
          console.log('Deal created:', deal);
        },
        error: (error) => {
          console.error('Error creating deal:', error);
        }
      });
    }
  }
}
```

### 3.6 CORS Configuration (Camunda)

For Option 3 to work, Camunda must be configured to allow CORS requests from the frontend.

**Camunda Configuration:**

```yaml
# application.yml or environment variables
camunda:
  rest:
    cors:
      enabled: true
      allowed-origins:
        - http://localhost:4200  # Angular dev server
        - https://your-production-domain.com
      allowed-methods:
        - GET
        - POST
        - PATCH
        - DELETE
      allowed-headers:
        - Authorization
        - Content-Type
      allow-credentials: true
```

---

## Keycloak Configuration

### Option 1: Service Account Setup

**1. Create user1 in camunda-platform realm:**
- Client ID: `camunda-backend-service`
- Client Secret: `<generate-secret>`
- Grant Type: `client_credentials`
- Roles: Assign appropriate Camunda roles

**2. Create user2 in LSCSAD realm:**
- Client ID: `camunda-callback-service`
- Client Secret: `<generate-secret>`
- Grant Type: `client_credentials`
- Roles: Assign appropriate backend API roles

### Option 2: Token Exchange Setup

**1. Enable Token Exchange in Keycloak:**
- Go to Realm Settings → Tokens
- Enable "Token Exchange" feature

**2. Configure Identity Provider:**
- In `camunda-platform` realm, add `LSCSAD` as Identity Provider
- Configure token exchange permissions
- Grant exchange permissions to backend client

**3. Configure Backend Client:**
- In `camunda-platform` realm, grant token exchange permissions
- In `LSCSAD` realm, grant token exchange permissions

### Option 3: Direct Frontend Access Setup

**1. Create Frontend Client in camunda-platform realm:**
- Client ID: `camunda-frontend-client`
- Client Type: `public` (for frontend applications)
- Valid Redirect URIs: `http://localhost:4200/*` (Angular app URLs)
- Web Origins: `http://localhost:4200` (for CORS)
- Standard Flow Enabled: `ON`
- Direct Access Grants Enabled: `ON` (if using username/password)

**2. Configure CORS in Camunda:**
- Enable CORS in Camunda REST API configuration
- Allow frontend origin
- Allow Authorization header

**3. Frontend Configuration:**
- Install `keycloak-js` package: `npm install keycloak-js`
- Configure both Keycloak instances (LSCSAD and camunda-platform)
- Initialize authentication on app startup

**4. Security Considerations:**
- Frontend tokens are stored in memory (not localStorage for security)
- Implement token refresh mechanism
- Handle token expiration gracefully
- Validate tokens before making API calls

---

## Usage Examples

### GraphQL Mutation (Create Deal)

```graphql
mutation {
  deal(input: {
    dealName: "Acme Corp Deal"
    dealType: "M&A"
    description: "Acquisition of Acme Corp"
    value: 1000000
    parties: [
      {
        name: "Acme Corp"
        type: "Company"
        role: "Target"
      }
    ]
    contacts: [
      {
        firstName: "John"
        lastName: "Doe"
        email: "john@acme.com"
      }
    ]
  }) {
    id
    processInstanceId
    dealName
    status
    currentStep
  }
}
```

### GraphQL Mutation (Update Deal)

```graphql
mutation {
  deal(
    processInstanceId: "123456"
    input: {
      dealName: "Updated Deal Name"
      dealType: "M&A"
      value: 1500000
    }
  ) {
    id
    processInstanceId
    dealName
    status
    currentStep
  }
}
```

---

## Environment Variables

```bash
# Option 1: Service Account
CAMUNDA_USER1_CLIENT_ID=camunda-backend-service
CAMUNDA_USER1_CLIENT_SECRET=<secret>
LSCSAD_USER2_CLIENT_ID=camunda-callback-service
LSCSAD_USER2_CLIENT_SECRET=<secret>

# Option 2: Token Exchange
CAMUNDA_CLIENT_ID=camunda-client

# Option 3: Direct Frontend Access
# Frontend environment variables (environment.ts)
KEYCLOAK_URL=http://keycloak:8080
LSCSAD_REALM=LSCSAD
LSCSAD_CLIENT_ID=angular-app
CAMUNDA_REALM=camunda-platform
CAMUNDA_CLIENT_ID=camunda-frontend-client
CAMUNDA_REST_API_URL=http://localhost:8081
```

---

## Summary

This implementation provides:

1. **Single GraphQL Mutation**: One mutation (`deal`) handles init/create/update operations
2. **Netflix DGS Integration**: Uses Netflix DGS framework for GraphQL resolvers
3. **Three Authentication Options**:
   - **Option 1**: Service Account Pattern (user1 → Camunda, user2 → Backend)
   - **Option 2**: Direct Token Exchange (LSCSAD ↔ camunda-platform)
   - **Option 3**: Direct Frontend Access (Frontend → Camunda directly with reusable service)
4. **Bidirectional Authentication**: Supports both Backend → Camunda and Camunda → Backend flows
5. **Reusable Service Pattern**: Option 3 provides a reusable Camunda REST client that can be used across multiple applications
6. **Flexible Configuration**: Switch between options via configuration

All implementation files are organized in `camunda-integration-examples/glass/` directory.

## Choosing the Right Option

**Use Option 1 (Service Account)** when:
- You need centralized control and validation
- Security is the top priority
- You want to hide Camunda implementation details from frontend

**Use Option 2 (Token Exchange)** when:
- You need user-level audit trails in Camunda
- You want to preserve user identity in Camunda operations
- You have Keycloak Token Exchange feature available

**Use Option 3 (Direct Frontend Access)** when:
- You need low latency (direct API calls)
- Multiple applications need Camunda access
- You want to build a reusable Camunda integration library
- Frontend needs real-time process updates
- You can properly secure frontend tokens
