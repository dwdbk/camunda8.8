# Process Instance Recovery and Resumption

## Problem Statement

When a user logs out or refreshes the page, they lose the process instance key. When they log in again, they need to:
1. Find their active/incomplete deal processes
2. Resume working on the deal from where they left off
3. Access the correct process instance

## Recommended Approaches

### Approach 1: Business Key + Backend Database (Recommended)

**Best Practice:** Store process instance key in backend database linked to user ID.

**Architecture:**
- Use business key (deal ID) when starting process
- Store process instance key in backend database (user-process mapping table)
- On login, query backend to get user's active processes
- Backend queries Camunda to verify process is still active

**Pros:**
- ✅ Fast lookup (database query)
- ✅ Can store additional metadata (status, last updated, etc.)
- ✅ Works across all options
- ✅ Can handle multiple active processes per user
- ✅ Backend can filter by user, status, date, etc.

**Cons:**
- ❌ Requires database table
- ❌ Need to sync with Camunda state

**Implementation:**

#### Backend Database Schema

```sql
CREATE TABLE user_deal_processes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(255) NOT NULL,
    deal_id VARCHAR(255) NOT NULL UNIQUE,
    process_instance_key BIGINT NOT NULL,
    business_key VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    completed_at TIMESTAMP NULL,
    INDEX idx_user_id (user_id),
    INDEX idx_deal_id (deal_id),
    INDEX idx_process_instance_key (process_instance_key),
    INDEX idx_status (status)
);
```

#### Backend Service

**File:** `service/DealProcessRepository.java`

```java
package com.example.camunda.glass.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class DealProcessRepository {
    
    private final JdbcTemplate jdbcTemplate;
    
    public void saveProcessMapping(String userId, String dealId, Long processInstanceKey, String businessKey) {
        String sql = """
            INSERT INTO user_deal_processes 
            (user_id, deal_id, process_instance_key, business_key, status)
            VALUES (?, ?, ?, ?, 'ACTIVE')
            ON DUPLICATE KEY UPDATE
            process_instance_key = VALUES(process_instance_key),
            status = 'ACTIVE',
            updated_at = CURRENT_TIMESTAMP
            """;
        
        jdbcTemplate.update(sql, userId, dealId, processInstanceKey, businessKey);
    }
    
    public Optional<DealProcessMapping> findByUserIdAndDealId(String userId, String dealId) {
        String sql = """
            SELECT * FROM user_deal_processes
            WHERE user_id = ? AND deal_id = ?
            """;
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            DealProcessMapping mapping = new DealProcessMapping();
            mapping.setUserId(rs.getString("user_id"));
            mapping.setDealId(rs.getString("deal_id"));
            mapping.setProcessInstanceKey(rs.getLong("process_instance_key"));
            mapping.setBusinessKey(rs.getString("business_key"));
            mapping.setStatus(rs.getString("status"));
            return mapping;
        }, userId, dealId).stream().findFirst();
    }
    
    public List<DealProcessMapping> findActiveProcessesByUserId(String userId) {
        String sql = """
            SELECT * FROM user_deal_processes
            WHERE user_id = ? AND status = 'ACTIVE'
            ORDER BY updated_at DESC
            """;
        
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            DealProcessMapping mapping = new DealProcessMapping();
            mapping.setUserId(rs.getString("user_id"));
            mapping.setDealId(rs.getString("deal_id"));
            mapping.setProcessInstanceKey(rs.getLong("process_instance_key"));
            mapping.setBusinessKey(rs.getString("business_key"));
            mapping.setStatus(rs.getString("status"));
            mapping.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            mapping.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
            return mapping;
        }, userId);
    }
    
    public void updateStatus(String dealId, String status) {
        String sql = """
            UPDATE user_deal_processes
            SET status = ?, updated_at = CURRENT_TIMESTAMP
            WHERE deal_id = ?
            """;
        
        jdbcTemplate.update(sql, status, dealId);
    }
    
    public void markCompleted(String dealId) {
        String sql = """
            UPDATE user_deal_processes
            SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE deal_id = ?
            """;
        
        jdbcTemplate.update(sql, dealId);
    }
    
    @lombok.Data
    public static class DealProcessMapping {
        private String userId;
        private String dealId;
        private Long processInstanceKey;
        private String businessKey;
        private String status;
        private java.time.Instant createdAt;
        private java.time.Instant updatedAt;
    }
}
```

#### Update Deal Process Service

**File:** `service/option1/DealProcessService.java` (add to existing service)

```java
// Add to DealProcessService
private final DealProcessRepository processRepository;

private DealResponse createDeal(DealInput input, String userId) {
    // ... existing code ...
    
    String businessKey = generateBusinessKey();
    String dealId = businessKey; // or generate separate deal ID
    
    CamundaRestClientService.ProcessInstanceDto processInstance = 
        camundaClient.startProcess(PROCESS_DEFINITION_KEY, variables, businessKey, camundaToken);
    
    // Store process mapping in database
    processRepository.saveProcessMapping(
        userId, 
        dealId, 
        processInstance.getProcessInstanceKey(), 
        businessKey
    );
    
    // ... rest of code ...
}

// Add method to get user's active processes
public List<DealProcessMapping> getActiveProcesses(String userId) {
    List<DealProcessMapping> mappings = processRepository.findActiveProcessesByUserId(userId);
    
    // Verify processes are still active in Camunda
    String camundaToken = camundaAuth.getAccessToken();
    return mappings.stream()
        .filter(mapping -> {
            try {
                CamundaRestClientService.ProcessInstanceDto instance = 
                    camundaClient.getProcessInstance(mapping.getProcessInstanceKey().toString(), camundaToken);
                if (instance == null || "COMPLETED".equals(instance.getState())) {
                    processRepository.markCompleted(mapping.getDealId());
                    return false;
                }
                return true;
            } catch (Exception e) {
                log.warn("Process instance {} not found, marking as completed", mapping.getProcessInstanceKey());
                processRepository.markCompleted(mapping.getDealId());
                return false;
            }
        })
        .collect(Collectors.toList());
}

// Add method to resume process by deal ID
public DealResponse resumeDeal(String dealId, String userId) {
    Optional<DealProcessMapping> mapping = processRepository.findByUserIdAndDealId(userId, dealId);
    
    if (mapping.isEmpty()) {
        throw new NotFoundException("Deal not found: " + dealId);
    }
    
    String camundaToken = camundaAuth.getAccessToken();
    
    // Verify process is still active
    CamundaRestClientService.ProcessInstanceDto instance = 
        camundaClient.getProcessInstance(mapping.get().getProcessInstanceKey().toString(), camundaToken);
    
    if (instance == null || "COMPLETED".equals(instance.getState())) {
        processRepository.markCompleted(dealId);
        throw new IllegalStateException("Process already completed");
    }
    
    // Get process variables and current step
    Map<String, Object> variables = camundaClient.getProcessVariables(
        mapping.get().getProcessInstanceKey().toString(), 
        camundaToken
    );
    
    List<CamundaRestClientService.TaskDto> activeTasks = 
        camundaClient.getActiveTasks(mapping.get().getProcessInstanceKey().toString(), camundaToken);
    
    return DealResponse.builder()
        .id(dealId)
        .processInstanceId(mapping.get().getProcessInstanceKey().toString())
        .dealName((String) variables.get("dealName"))
        .dealType((String) variables.get("dealType"))
        .description((String) variables.get("description"))
        .value((Double) variables.get("value"))
        .status("IN_PROGRESS")
        .currentStep(activeTasks.isEmpty() ? "complete" : getStepFromTask(activeTasks.get(0)))
        .parties(parseJsonList(variables.get("parties")))
        .contacts(parseJsonList(variables.get("contacts")))
        .createdAt((String) variables.get("initiatedAt"))
        .updatedAt(Instant.now().toString())
        .build();
}
```

#### GraphQL Query for Active Processes

**File:** `graphql/DealProcessDataFetcher.java` (add to existing resolver)

```java
@DgsQuery
public List<DealResponse> myActiveDeals() {
    Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    String userId = extractUserId(jwt);
    
    List<DealProcessMapping> mappings = dealProcessService.getActiveProcesses(userId);
    
    return mappings.stream()
        .map(mapping -> {
            try {
                return dealProcessService.resumeDeal(mapping.getDealId(), userId);
            } catch (Exception e) {
                log.error("Failed to resume deal {}", mapping.getDealId(), e);
                return null;
            }
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
}

@DgsQuery
public DealResponse resumeDeal(@InputArgument String dealId) {
    Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    String userId = extractUserId(jwt);
    
    return dealProcessService.resumeDeal(dealId, userId);
}
```

#### GraphQL Schema Update

**File:** `graphql/schema.graphqls` (add to existing schema)

```graphql
type Query {
  deal(processInstanceId: String!): Deal
  deals(status: DealStatus): [Deal!]!
  myActiveDeals: [Deal!]!  # Get user's active deals
  resumeDeal(dealId: String!): Deal!  # Resume deal by deal ID
}
```

---

### Approach 2: Camunda Search API with Business Key

**Alternative:** Use Camunda's search API to find process instances by business key.

**Pros:**
- ✅ No database required
- ✅ Always in sync with Camunda state
- ✅ Works for Options 3 and 4 (frontend direct access)

**Cons:**
- ❌ Requires search API call on every login
- ❌ Slower than database lookup
- ❌ May need to filter by user (requires process variable query)

**Implementation:**

#### Backend Service Method

```java
public List<DealResponse> findUserActiveDeals(String userId) {
    String camundaToken = camundaAuth.getAccessToken();
    
    // Search for process instances with initiatedBy = userId and state = ACTIVE
    SearchRequest searchRequest = new SearchRequest();
    searchRequest.setFilter(new ProcessInstanceFilterDto());
    searchRequest.getFilter().setVariableFilters(List.of(
        new VariableFilterDto()
            .setName("initiatedBy")
            .setValue(userId)
            .setOperator(VariableFilterDto.Operator.EQ)
    ));
    searchRequest.setSort(List.of(
        new SortRequest()
            .setField("startTime")
            .setOrder(SortRequest.Order.DESC)
    ));
    
    // Call Camunda search API
    ProcessInstanceSearchResponse response = webClient.post()
        .uri(camundaBaseUrl + "/v1/process-instances/search")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + camundaToken)
        .bodyValue(searchRequest)
        .retrieve()
        .bodyToMono(ProcessInstanceSearchResponse.class)
        .block();
    
    // Filter by state and map to DealResponse
    return response.getItems().stream()
        .filter(item -> "ACTIVE".equals(item.getState()))
        .map(item -> {
            Map<String, Object> variables = camundaClient.getProcessVariables(
                item.getProcessInstanceKey().toString(), 
                camundaToken
            );
            List<CamundaRestClientService.TaskDto> tasks = 
                camundaClient.getActiveTasks(item.getProcessInstanceKey().toString(), camundaToken);
            
            return DealResponse.builder()
                .id((String) variables.get("businessKey"))
                .processInstanceId(item.getProcessInstanceKey().toString())
                .dealName((String) variables.get("dealName"))
                .dealType((String) variables.get("dealType"))
                .status("IN_PROGRESS")
                .currentStep(tasks.isEmpty() ? "complete" : getStepFromTask(tasks.get(0)))
                .createdAt((String) variables.get("initiatedAt"))
                .updatedAt(Instant.now().toString())
                .build();
        })
        .collect(Collectors.toList());
}
```

#### Camunda Search API Request Format

```json
POST /v1/process-instances/search
{
  "filter": {
    "variableFilters": [
      {
        "name": "initiatedBy",
        "operator": "eq",
        "value": "user@example.com"
      },
      {
        "name": "businessKey",
        "operator": "like",
        "value": "DEAL-%"
      }
    ],
    "state": "ACTIVE"
  },
  "sort": [
    {
      "field": "startTime",
      "order": "DESC"
    }
  ],
  "pageSize": 50
}
```

---

### Approach 3: Business Key Only (Simple)

**Simplest:** Use business key (deal ID) to find process instance.

**Pros:**
- ✅ Very simple
- ✅ No database required
- ✅ Business key is human-readable

**Cons:**
- ❌ Need to know deal ID to resume
- ❌ Cannot list all user's deals easily
- ❌ Requires search API call

**Implementation:**

```java
public DealResponse resumeDealByBusinessKey(String businessKey, String userId) {
    String camundaToken = camundaAuth.getAccessToken();
    
    // Search by business key
    SearchRequest searchRequest = new SearchRequest();
    searchRequest.setFilter(new ProcessInstanceFilterDto());
    searchRequest.getFilter().setBusinessKey(businessKey);
    
    ProcessInstanceSearchResponse response = webClient.post()
        .uri(camundaBaseUrl + "/v1/process-instances/search")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + camundaToken)
        .bodyValue(searchRequest)
        .retrieve()
        .bodyToMono(ProcessInstanceSearchResponse.class)
        .block();
    
    if (response.getItems().isEmpty()) {
        throw new NotFoundException("Process not found for business key: " + businessKey);
    }
    
    ProcessInstanceDto instance = response.getItems().get(0);
    
    // Verify ownership
    Map<String, Object> variables = camundaClient.getProcessVariables(
        instance.getProcessInstanceKey().toString(), 
        camundaToken
    );
    
    String initiatedBy = (String) variables.get("initiatedBy");
    if (!userId.equals(initiatedBy)) {
        throw new SecurityException("User not authorized to access this process");
    }
    
    // Return deal response (same as resumeDeal method above)
    // ...
}
```

---

## Frontend Implementation

### Option 1: Store Process Instance Key in Local Storage (Temporary)

**Simple but not recommended for production:**

```typescript
// On process creation
const deal = await dealService.createOrUpdateDeal(null, input);
localStorage.setItem(`deal_${deal.id}`, deal.processInstanceId);

// On login/resume
const processInstanceId = localStorage.getItem(`deal_${dealId}`);
if (processInstanceId) {
  const deal = await dealService.getDeal(processInstanceId);
}
```

**Limitations:**
- ❌ Lost on browser clear
- ❌ Not shared across devices
- ❌ No server-side persistence

### Option 2: Query Backend for Active Deals (Recommended)

**Frontend Service:**

```typescript
// Add to DealProcessService
getMyActiveDeals(): Observable<DealResponse[]> {
  return this.http.get<DealResponse[]>(`${this.apiUrl}/my-active-deals`);
}

resumeDeal(dealId: string): Observable<DealResponse> {
  return this.http.get<DealResponse>(`${this.apiUrl}/resume/${dealId}`);
}
```

**Component:**

```typescript
ngOnInit() {
  // On login, load user's active deals
  this.dealService.getMyActiveDeals().subscribe({
    next: (deals) => {
      this.activeDeals = deals;
      if (deals.length > 0) {
        // Show list of active deals or auto-resume most recent
        this.resumeMostRecentDeal(deals[0]);
      }
    },
    error: (error) => {
      console.error('Failed to load active deals:', error);
    }
  });
}

resumeMostRecentDeal(deal: DealResponse) {
  this.dealService.resumeDeal(deal.id).subscribe({
    next: (resumedDeal) => {
      this.currentDeal = resumedDeal;
      this.router.navigate(['/deals', resumedDeal.processInstanceId, resumedDeal.currentStep]);
    }
  });
}
```

---

## Comparison of Approaches

| Approach | Database Required | Performance | Complexity | Best For |
|----------|------------------|------------|------------|----------|
| **Approach 1: Backend DB** | ✅ Yes | ⚡ Fast | Medium | Production, all options |
| **Approach 2: Search API** | ❌ No | 🐌 Slower | Low | Options 3/4, simple cases |
| **Approach 3: Business Key** | ❌ No | 🐌 Slower | Very Low | Simple cases, known deal ID |

## Recommendation

**Use Approach 1 (Backend Database)** for production because:
1. ✅ Fast lookup (database query vs API call)
2. ✅ Can store additional metadata
3. ✅ Works with all authentication options
4. ✅ Can implement caching
5. ✅ Better for analytics and reporting
6. ✅ Can handle edge cases (deleted processes, etc.)

**Use Approach 2 (Search API)** if:
- You don't want to maintain a database table
- You're using Options 3 or 4 (frontend direct access)
- You have low volume of processes

**Use Approach 3 (Business Key)** if:
- User always knows their deal ID
- Very simple use case
- No need to list all user's deals

---

## Complete Flow Example

### User Journey

1. **User starts deal:**
   ```
   Frontend → GraphQL mutation → Backend → Camunda
   Backend stores: userId, dealId, processInstanceKey → Database
   ```

2. **User logs out or refreshes:**
   ```
   Process instance key lost from frontend
   Process continues in Camunda
   ```

3. **User logs in again:**
   ```
   Frontend → Query myActiveDeals → Backend
   Backend queries database → Gets user's active processes
   Backend verifies with Camunda → Returns active deals
   Frontend shows list → User selects deal → Resume
   ```

4. **Resume deal:**
   ```
   Frontend → resumeDeal(dealId) → Backend
   Backend gets processInstanceKey from database
   Backend queries Camunda → Gets current state
   Frontend navigates to current step
   ```

---

## Implementation Checklist

- [ ] Create database table for user-process mapping
- [ ] Update DealProcessService to save process mapping on creation
- [ ] Implement getActiveProcesses() method
- [ ] Implement resumeDeal() method
- [ ] Add GraphQL queries (myActiveDeals, resumeDeal)
- [ ] Update frontend service to query active deals
- [ ] Update frontend component to load active deals on login
- [ ] Handle process completion (mark as completed in database)
- [ ] Add cleanup job for orphaned processes (optional)

---

## Error Handling

### Process Not Found
- Process was completed → Mark as completed in database
- Process was deleted → Remove from database
- Process doesn't belong to user → Return 403 Forbidden

### Multiple Active Processes
- Show list to user
- Allow user to select which process to resume
- Auto-resume most recent process (optional)

### Process Already Completed
- Check process state before resuming
- Return appropriate message to user
- Offer to start new process
