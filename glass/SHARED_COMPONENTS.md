# Shared Components

This document contains shared components used across all integration options.

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

## Shared Camunda REST Client Service

**File:** `service/CamundaRestClientService.java`

This service is shared by Options 1 and 2 (backend implementations). It provides methods to interact with Camunda 8.8 REST API.

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
 * Used by Options 1 (Service Account) and Option 2 (Token Exchange).
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
     * Token parameter comes from authentication service (Option 1) or token exchange (Option 2).
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

## Configuration Files

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

## Callback Controller

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
 * Used by all options for process completion callbacks.
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
     * Authentication depends on the option being used.
     */
    @PostMapping("/{businessKey}/status-update")
    public ResponseEntity<Map<String, String>> updateDealStatus(
            @PathVariable String businessKey,
            @RequestBody Map<String, Object> callbackData) {
        
        log.info("Received callback for deal: {}", businessKey);
        
        // Extract authenticated user
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
 * Routes to appropriate service based on configured option.
 */
@Slf4j
@DgsComponent
@RequiredArgsConstructor
public class DealProcessDataFetcher {

    @Value("${camunda.auth.option:option1}")
    private String authOption;
    
    // Inject the appropriate service based on configuration
    // See individual option files for service implementations
    
    /**
     * Single mutation for init/create/update deal operations.
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
        
        // Route to appropriate service based on option
        // Implementation details in individual option files
        
        throw new UnsupportedOperationException("Configure service for option: " + authOption);
    }
    
    @DgsQuery
    public DealResponse deal(@InputArgument String processInstanceId) {
        // Implementation for querying deal
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
