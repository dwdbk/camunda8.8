package com.example.camunda.service;

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
 * Service for interacting with Camunda 8.8 REST API.
 * Handles process instances, tasks, and variables.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CamundaRestClientService {
    
    private final WebClient webClient;
    private final CamundaAuthenticationService authService;
    private final ObjectMapper objectMapper;
    
    @Value("${camunda.rest.base-url}")
    private String camundaBaseUrl;
    
    /**
     * Start a new process instance.
     * 
     * @param processDefinitionKey Process definition key
     * @param variables Process variables
     * @param businessKey Business key (optional)
     * @return Process instance response
     */
    public ProcessInstanceDto startProcess(String processDefinitionKey, 
                                          Map<String, Object> variables,
                                          String businessKey) {
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
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + authService.getAccessToken())
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
     * 
     * @param processInstanceKey Process instance key
     * @return List of active tasks
     */
    public List<TaskDto> getActiveTasks(String processInstanceKey) {
        log.debug("Getting active tasks for process instance: {}", processInstanceKey);
        
        List<TaskDto> tasks = webClient.get()
            .uri(uriBuilder -> uriBuilder
                .path(camundaBaseUrl + "/v1/tasks")
                .queryParam("processInstanceKey", processInstanceKey)
                .build())
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + authService.getAccessToken())
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
     * 
     * @param taskKey Task key
     * @param variables Variables to set (optional)
     */
    public void completeTask(Long taskKey, Map<String, Object> variables) {
        log.debug("Completing task: taskKey={}", taskKey);
        
        Map<String, Object> requestBody = new HashMap<>();
        if (variables != null && !variables.isEmpty()) {
            requestBody.put("variables", convertVariables(variables));
        }
        
        webClient.post()
            .uri(camundaBaseUrl + "/v1/tasks/" + taskKey + "/complete")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + authService.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(Void.class)
            .block();
        
        log.info("Completed task: taskKey={}", taskKey);
    }
    
    /**
     * Update process instance variables.
     * 
     * @param processInstanceKey Process instance key
     * @param variables Variables to update
     */
    public void updateProcessVariables(String processInstanceKey, 
                                      Map<String, Object> variables) {
        log.debug("Updating process variables for instance: {}", processInstanceKey);
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("variables", convertVariables(variables));
        
        webClient.patch()
            .uri(camundaBaseUrl + "/v1/process-instances/" + processInstanceKey + "/variables")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + authService.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(requestBody)
            .retrieve()
            .bodyToMono(Void.class)
            .block();
        
        log.debug("Updated process variables for instance: {}", processInstanceKey);
    }
    
    /**
     * Get process instance variables.
     * 
     * @param processInstanceKey Process instance key
     * @return Map of process variables
     */
    public Map<String, Object> getProcessVariables(String processInstanceKey) {
        log.debug("Getting process variables for instance: {}", processInstanceKey);
        
        Map<String, Object> response = webClient.get()
            .uri(camundaBaseUrl + "/v1/process-instances/" + processInstanceKey + "/variables")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + authService.getAccessToken())
            .retrieve()
            .bodyToMono(Map.class)
            .block();
        
        // Parse variables from Camunda response format
        // Response format: { "variables": { "key": { "value": ..., "type": ... } } }
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
     * Get process instance by key.
     * 
     * @param processInstanceKey Process instance key
     * @return Process instance DTO
     */
    public ProcessInstanceDto getProcessInstance(String processInstanceKey) {
        log.debug("Getting process instance: {}", processInstanceKey);
        
        ProcessInstanceDto response = webClient.get()
            .uri(camundaBaseUrl + "/v1/process-instances/" + processInstanceKey)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + authService.getAccessToken())
            .retrieve()
            .bodyToMono(ProcessInstanceDto.class)
            .block();
        
        return response;
    }
    
    /**
     * Convert variables to Camunda format.
     * Camunda expects variables in format: { "key": { "value": ..., "type": "String" } }
     */
    private Map<String, Map<String, Object>> convertVariables(Map<String, Object> variables) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        
        variables.forEach((key, value) -> {
            Map<String, Object> variableMap = new HashMap<>();
            variableMap.put("value", value);
            
            // Determine type
            String type = "String";
            if (value instanceof Number) {
                type = value instanceof Long || value instanceof Integer ? "Long" : "Double";
            } else if (value instanceof Boolean) {
                type = "Boolean";
            } else if (value instanceof Map || value instanceof List) {
                type = "Json";
                // Serialize complex objects to JSON string
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
    
    /**
     * Process instance DTO.
     */
    public static class ProcessInstanceDto {
        private Long processInstanceKey;
        private String bpmnProcessId;
        private Long processDefinitionKey;
        private String businessKey;
        private String state;
        
        // Getters and setters
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
    
    /**
     * Task DTO.
     */
    public static class TaskDto {
        private Long key;
        private String name;
        private String taskDefinitionId;
        private String processInstanceKey;
        private String assignee;
        private String formKey;
        
        // Getters and setters
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
