# Camunda 8.8 Integration Guide: Angular + Spring Boot + Keycloak Cross-Realm Authentication

## Executive Summary

This guide provides a comprehensive analysis and implementation strategy for integrating an Angular front-end (authenticated via Keycloak realm `LSCSAD`) with a Java Spring Boot backend and Camunda 8.8 process engine (authenticated via Keycloak realm `camunda-platform`). The integration enables users to initiate and progress through a multi-step deal process while maintaining secure authentication across both realms.

---

## Reasoning

### 1. Authentication Bridging

**Problem Statement:**
- Users authenticate to the Angular application via Keycloak realm `LSCSAD`
- Camunda 8.8 APIs require authentication via Keycloak realm `camunda-platform`
- These are separate realms with separate user stores, tokens, and client configurations
- A token from `LSCSAD` realm cannot directly authenticate requests to Camunda APIs protected by `camunda-platform` realm

**Analysis of Authentication Strategies:**

**Option A: Direct Token Exchange (Not Recommended)**
- Frontend attempts to exchange `LSCSAD` token for `camunda-platform` token
- Requires user to authenticate twice (poor UX)
- Security risk: exposes Camunda realm credentials to frontend
- Complex token management in browser storage
- **Verdict:** Not viable for production

**Option B: Service Account Pattern (Recommended)**
- Backend uses a service account/client credentials grant to authenticate with Camunda realm
- Backend maps authenticated `LSCSAD` user identity to Camunda user identity
- User identity mapping can be done via:
  - Email address (if same across realms)
  - Username (if synchronized)
  - Custom claim mapping (e.g., `preferred_username`, `sub`)
  - External user ID stored in both realms
- Backend maintains Camunda realm credentials securely (never exposed to frontend)
- **Verdict:** Secure, maintainable, scalable

**Option C: Token Delegation/Impersonation**
- Backend validates `LSCSAD` token
- Backend impersonates user in Camunda realm using service account with delegation rights
- Requires Keycloak token exchange or impersonation features
- More complex setup but provides audit trail
- **Verdict:** Good for enterprise scenarios requiring user-level audit logs

**Recommended Approach:**
Use **Option B (Service Account Pattern)** with user identity mapping. The backend:
1. Validates incoming `LSCSAD` realm token from Angular
2. Extracts user identity (email/username/sub)
3. Uses service account credentials to authenticate with Camunda APIs
4. Maps user identity when creating/querying process instances
5. Ensures all Camunda operations are performed on behalf of the authenticated user

**Implementation Considerations:**
- Store Camunda realm client credentials securely (environment variables, secrets management)
- Implement user identity mapping service/utility
- Handle cases where user doesn't exist in Camunda realm (create user or use service account identity)
- Cache Camunda realm access tokens with appropriate TTL
- Implement token refresh mechanism for long-running sessions

---

### 2. API Access Pattern

**Problem Statement:**
Determine whether Angular should call Camunda APIs directly or route all requests through the Spring Boot backend.

**Option A: Frontend Direct API Calls**
**Pros:**
- Reduced backend load
- Lower latency (fewer hops)
- Simpler backend code
- Direct access to Camunda REST API features

**Cons:**
- **Critical:** Requires exposing Camunda realm tokens to frontend (security risk)
- Frontend must handle Camunda authentication complexity
- Cross-origin issues (CORS configuration required)
- No centralized business logic or validation
- Difficult to implement cross-realm authentication
- Frontend becomes tightly coupled to Camunda API structure
- Harder to implement rate limiting, caching, or request transformation
- **Verdict:** Not recommended due to security and authentication complexity

**Option B: Backend Proxy Pattern (Recommended)**
**Pros:**
- **Security:** Camunda realm credentials never exposed to frontend
- Centralized authentication and authorization logic
- Backend can implement business rules, validation, and transformation
- Easier to implement user identity mapping
- Single point of control for Camunda API access
- Can implement caching, rate limiting, and request aggregation
- Easier to handle errors and provide user-friendly messages
- Backend can implement audit logging and monitoring
- Frontend remains decoupled from Camunda API details
- **Verdict:** Strongly recommended for production

**Cons:**
- Additional network hop (minimal impact in most scenarios)
- Backend must implement proxy endpoints
- Slightly more complex backend code

**Option C: Hybrid Approach**
- Frontend calls Camunda APIs directly for read-only operations (queries, process definitions)
- Backend handles all write operations (start process, complete tasks, update variables)
- **Verdict:** Possible but adds complexity; not recommended unless specific performance requirements

**Recommended Approach:**
Use **Option B (Backend Proxy Pattern)** exclusively. All Camunda interactions should flow through Spring Boot backend endpoints. This provides:
- Security: Camunda realm tokens never leave the backend
- Maintainability: Single source of truth for Camunda integration logic
- Flexibility: Easy to change Camunda API usage without frontend changes
- User Experience: Backend can transform Camunda errors into user-friendly messages

**API Endpoint Structure:**
```
Frontend → Backend Endpoints:
- POST /api/deals/initiate          → Start Camunda process
- GET  /api/deals/{processInstanceId}/status → Get process status
- POST /api/deals/{processInstanceId}/deal-info → Submit deal info form
- POST /api/deals/{processInstanceId}/parties → Submit parties form
- POST /api/deals/{processInstanceId}/contacts → Submit contacts form
- POST /api/deals/{processInstanceId}/complete → Complete process
- GET  /api/deals/{processInstanceId}/current-step → Get current step info
```

---

### 3. Process Lifecycle Management

**Problem Statement:**
Correlate frontend user actions (form submissions, navigation) with Camunda process instance state and task completion.

**Process Flow Design:**

**Step 1: Process Initialization**
- User clicks "Initialize Deal" button
- Frontend calls `POST /api/deals/initiate`
- Backend:
  - Validates user authentication (LSCSAD token)
  - Maps user identity to Camunda user
  - Starts Camunda process instance using Camunda REST API
  - Sets initial process variables (userId, businessKey, timestamp)
  - Returns process instance ID and current step information
- Frontend stores process instance ID (session storage or state management)
- Frontend navigates to "Deal Info" form

**Step 2: Deal Info Form Submission**
- User fills and submits deal info form
- Frontend calls `POST /api/deals/{processInstanceId}/deal-info` with form data
- Backend:
  - Validates process instance exists and belongs to user
  - Updates Camunda process variables with deal info
  - Completes current user task (if using user tasks) or advances process (if using service tasks)
  - Retrieves next active task/step
  - Returns next step information
- Frontend navigates to "Add Parties" form

**Step 3: Parties Form Submission**
- Similar to Step 2, but for parties data
- Backend updates process variables and advances process
- Frontend navigates to "Add Contacts" form

**Step 4: Contacts Form Submission**
- Similar to Step 2, but for contacts data
- Backend updates process variables and advances process
- Frontend navigates to "Complete" step

**Step 5: Process Completion**
- User clicks "Complete" button
- Frontend calls `POST /api/deals/{processInstanceId}/complete`
- Backend:
  - Validates all required data is present
  - Completes final task
  - Process instance completes
  - Returns completion status and summary
- Frontend shows success message and redirects to deal list/dashboard

**Process State Tracking:**

**Option A: User Tasks (Recommended)**
- Each step is a User Task in Camunda BPMN
- Backend queries active tasks for process instance
- Frontend displays form based on current active task
- Form submission completes the task, advancing process
- **Pros:** Native Camunda task management, built-in task assignment, audit trail
- **Cons:** Requires task querying, slightly more complex

**Option B: Process Variables + Service Tasks**
- Process uses service tasks with process variables
- Frontend tracks step via process variable (e.g., `currentStep: "deal-info"`)
- Backend updates process variables and triggers process advancement
- **Pros:** Simpler task management, direct variable control
- **Cons:** Less native Camunda task features, manual step tracking

**Option C: Message Events**
- Process uses message events to advance
- Frontend sends messages via backend
- Backend correlates messages to process instance
- **Pros:** Decoupled, event-driven
- **Cons:** More complex, requires message correlation

**Recommended Approach:**
Use **Option A (User Tasks)** for better integration with Camunda's task management and audit capabilities. Each form step corresponds to a User Task in the BPMN process.

**Process Variable Strategy:**
- Store form data as process variables (JSON or individual variables)
- Use consistent naming: `dealInfo`, `parties`, `contacts`
- Include metadata: `initiatedBy`, `initiatedAt`, `lastUpdatedAt`
- Use business key for external correlation (e.g., deal ID)

**Error Handling:**
- If process instance not found: Return 404, allow user to restart
- If process already completed: Return appropriate message, prevent further updates
- If task not found: Return error, allow user to refresh or restart
- If validation fails: Return validation errors, allow user to correct and resubmit
- Network errors: Implement retry logic, show user-friendly error messages

---

### 4. User Experience

**Problem Statement:**
Design frontend navigation and form flow that stays synchronized with Camunda process state.

**Navigation Flow:**

**Initial State:**
- User is on dashboard/deal list page
- "Initialize Deal" button is visible

**After Process Start:**
- User is redirected to `/deals/{processInstanceId}/deal-info`
- Form is pre-populated if returning user (optional)
- Process instance ID stored in application state

**Form Submission Flow:**
1. User submits form
2. Frontend shows loading indicator
3. Backend processes request
4. On success:
   - Frontend receives next step information
   - Frontend navigates to next form (e.g., `/deals/{processInstanceId}/parties`)
   - Form data is cleared or pre-populated based on backend response
5. On error:
   - Frontend displays error message
   - User can correct and resubmit
   - Process state remains unchanged

**Route Structure:**
```
/deals/initiate                    → Initialize new deal
/deals/:processInstanceId/deal-info → Deal information form
/deals/:processInstanceId/parties   → Parties form
/deals/:processInstanceId/contacts  → Contacts form
/deals/:processInstanceId/complete  → Completion confirmation
/deals/:processInstanceId/summary   → Deal summary (after completion)
```

**State Management:**

**Option A: Route-Based State**
- Process instance ID in URL
- Each route loads current step from backend
- Backend validates step and returns appropriate form data
- **Pros:** Bookmarkable URLs, browser back/forward works
- **Cons:** Requires backend validation on each route load

**Option B: Application State Management**
- Store process instance ID and current step in application state (NgRx, Redux, or service)
- Routes are determined by state
- **Pros:** Faster navigation, less backend calls
- **Cons:** State can become stale, requires synchronization

**Recommended Approach:**
Use **Hybrid Approach:**
- Process instance ID in URL (for bookmarking and direct access)
- On route load, validate with backend and get current step
- If user tries to access wrong step, redirect to correct step
- Store minimal state in application (process instance ID, current step)
- Always validate step with backend before form submission

**Form Persistence:**
- Optionally save form data to local storage as user types (auto-save)
- On form load, check for saved data and pre-populate
- Clear saved data on successful submission
- Handle browser refresh gracefully

**Loading States:**
- Show loading spinner during API calls
- Disable form submission during processing
- Provide clear feedback on success/error

**Error Recovery:**
- If process instance not found: Offer to start new process
- If process completed: Show summary, prevent further edits
- If validation errors: Highlight fields, show specific error messages
- Network errors: Show retry option

---

### 5. Security

**Problem Statement:**
Ensure secure authentication, authorization, and data protection across the integration.

**Security Requirements:**

**1. Authentication Security:**
- **Frontend → Backend:**
  - All requests include `LSCSAD` realm JWT token in `Authorization` header
  - Backend validates token signature, expiration, and issuer
  - Backend extracts user identity from token claims
- **Backend → Camunda:**
  - Backend uses service account credentials (client ID + secret) for Camunda realm
  - Credentials stored securely (environment variables, Kubernetes secrets, Vault)
  - Never expose Camunda realm tokens to frontend
  - Implement token caching with TTL to reduce Keycloak load
  - Implement token refresh before expiration

**2. Authorization Security:**
- **User Identity Mapping:**
  - Map `LSCSAD` user to Camunda user consistently
  - Use immutable identifier (email, username, or custom claim)
  - Validate mapping exists before process operations
  - Handle cases where user doesn't exist in Camunda realm
- **Process Instance Access Control:**
  - Backend validates user owns/created process instance before operations
  - Query process instances filtered by user identity
  - Prevent users from accessing other users' process instances
  - Implement role-based access if needed (e.g., managers can view all deals)

**3. Data Protection:**
- **In Transit:**
  - Use HTTPS/TLS for all communications
  - Validate SSL certificates
  - Use secure headers (HSTS, CSP)
- **At Rest:**
  - Encrypt sensitive process variables if required
  - Follow data retention policies
  - Implement data masking for sensitive fields in logs

**4. API Security:**
- **Rate Limiting:**
  - Implement rate limiting on backend endpoints
  - Prevent abuse and DoS attacks
  - Use token bucket or sliding window algorithm
- **Input Validation:**
  - Validate all input from frontend
  - Sanitize data before storing in process variables
  - Prevent injection attacks (SQL, NoSQL, XSS)
- **CORS Configuration:**
  - Configure CORS to allow only trusted origins
  - Use credentials: true for cookie-based auth (if used)
  - Restrict allowed methods and headers

**5. Audit and Logging:**
- Log all process operations with user identity
- Include process instance ID, operation type, timestamp
- Store logs securely, implement log rotation
- Enable audit trail in Camunda for compliance

**6. Error Handling Security:**
- Don't expose internal errors or stack traces to frontend
- Don't leak information about process instances, users, or system structure
- Return generic error messages to frontend
- Log detailed errors server-side for debugging

**Security Best Practices:**
1. **Principle of Least Privilege:**
   - Service account should have minimum required permissions in Camunda
   - Users should only access their own process instances
2. **Defense in Depth:**
   - Multiple layers of security (network, application, data)
   - Validate at each layer
3. **Secure by Default:**
   - Fail securely (deny access on error)
   - Use secure defaults for all configurations
4. **Regular Security Audits:**
   - Review authentication/authorization logic regularly
   - Update dependencies for security patches
   - Conduct penetration testing

---

## Recommendation

### Best Practice Integration Approach

**Architecture Overview:**
```
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│   Angular   │────────▶│ Spring Boot  │────────▶│  Camunda 8  │
│   Frontend  │         │   Backend    │         │   Engine    │
└─────────────┘         └──────────────┘         └─────────────┘
      │                        │                        │
      │                        │                        │
      ▼                        ▼                        ▼
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│  Keycloak   │         │  Keycloak    │         │  Keycloak   │
│ Realm:      │         │  Service     │         │ Realm:      │
│ LSCSAD      │         │  Account     │         │ camunda-    │
│             │         │  (Backend)   │         │ platform    │
└─────────────┘         └──────────────┘         └─────────────┘
```

**Implementation Steps:**

**1. Backend Configuration (Spring Boot)**

**Step 1.1: Add Dependencies**
```xml
<dependencies>
    <!-- Camunda 8 REST Client -->
    <dependency>
        <groupId>io.camunda</groupId>
        <artifactId>camunda-zeebe-client-java</artifactId>
        <version>8.8.0</version>
    </dependency>
    
    <!-- Spring Security for Keycloak -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
    </dependency>
    
    <!-- HTTP Client for Camunda REST API -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>
</dependencies>
```

**Step 1.2: Configure Keycloak Integration**
```yaml
# application.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://keycloak:8080/realms/LSCSAD
          jwk-set-uri: http://keycloak:8080/realms/LSCSAD/protocol/openid-connect/certs

camunda:
  zeebe:
    gateway-address: localhost:26500
    security:
      enabled: true
      keycloak-url: http://keycloak:8080
      realm: camunda-platform
      client-id: ${CAMUNDA_CLIENT_ID}
      client-secret: ${CAMUNDA_CLIENT_SECRET}
  
  rest:
    base-url: http://localhost:8081
    keycloak-url: http://keycloak:8080
    realm: camunda-platform
    client-id: ${CAMUNDA_CLIENT_ID}
    client-secret: ${CAMUNDA_CLIENT_SECRET}
```

**Step 1.3: Create Camunda Authentication Service**
```java
@Service
public class CamundaAuthenticationService {
    
    private final WebClient webClient;
    private final String keycloakUrl;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private AccessToken cachedToken;
    private LocalDateTime tokenExpiry;
    
    public String getAccessToken() {
        if (cachedToken == null || isTokenExpired()) {
            refreshToken();
        }
        return cachedToken.getToken();
    }
    
    private void refreshToken() {
        // Use client credentials grant to get token
        // Store token and expiry time
    }
}
```

**Step 1.4: Create User Identity Mapping Service**
```java
@Service
public class UserIdentityMappingService {
    
    public String mapToCamundaUser(Jwt jwt) {
        // Extract user identity from LSCSAD token
        String email = jwt.getClaimAsString("email");
        String username = jwt.getClaimAsString("preferred_username");
        String sub = jwt.getClaimAsString("sub");
        
        // Map to Camunda user (use email, username, or create mapping)
        return email; // or username, or lookup in mapping table
    }
}
```

**Step 1.5: Create Camunda REST Client Service**
```java
@Service
public class CamundaRestClientService {
    
    private final WebClient webClient;
    private final CamundaAuthenticationService authService;
    
    public ProcessInstanceDto startProcess(String processDefinitionKey, 
                                          Map<String, Object> variables,
                                          String businessKey) {
        return webClient.post()
            .uri("/v1/process-instances")
            .header("Authorization", "Bearer " + authService.getAccessToken())
            .bodyValue(createStartProcessRequest(processDefinitionKey, variables, businessKey))
            .retrieve()
            .bodyToMono(ProcessInstanceDto.class)
            .block();
    }
    
    public void completeTask(String taskId, Map<String, Object> variables) {
        webClient.post()
            .uri("/v1/tasks/{taskId}/complete", taskId)
            .header("Authorization", "Bearer " + authService.getAccessToken())
            .bodyValue(createCompleteTaskRequest(variables))
            .retrieve()
            .bodyToMono(Void.class)
            .block();
    }
    
    public List<TaskDto> getActiveTasks(String processInstanceId) {
        return webClient.get()
            .uri("/v1/tasks?processInstanceId={processInstanceId}", processInstanceId)
            .header("Authorization", "Bearer " + authService.getAccessToken())
            .retrieve()
            .bodyToFlux(TaskDto.class)
            .collectList()
            .block();
    }
}
```

**Step 1.6: Create Deal Process Controller**
```java
@RestController
@RequestMapping("/api/deals")
@PreAuthorize("hasRole('USER')")
public class DealProcessController {
    
    private final DealProcessService dealProcessService;
    private final UserIdentityMappingService userMappingService;
    
    @PostMapping("/initiate")
    public ResponseEntity<DealProcessResponse> initiateDeal(
            @AuthenticationPrincipal Jwt jwt) {
        String camundaUser = userMappingService.mapToCamundaUser(jwt);
        DealProcessResponse response = dealProcessService.startDealProcess(camundaUser);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/{processInstanceId}/deal-info")
    public ResponseEntity<ProcessStepResponse> submitDealInfo(
            @PathVariable String processInstanceId,
            @RequestBody DealInfoDto dealInfo,
            @AuthenticationPrincipal Jwt jwt) {
        String camundaUser = userMappingService.mapToCamundaUser(jwt);
        ProcessStepResponse response = dealProcessService.submitDealInfo(
            processInstanceId, dealInfo, camundaUser);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/{processInstanceId}/parties")
    public ResponseEntity<ProcessStepResponse> submitParties(
            @PathVariable String processInstanceId,
            @RequestBody PartiesDto parties,
            @AuthenticationPrincipal Jwt jwt) {
        String camundaUser = userMappingService.mapToCamundaUser(jwt);
        ProcessStepResponse response = dealProcessService.submitParties(
            processInstanceId, parties, camundaUser);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/{processInstanceId}/contacts")
    public ResponseEntity<ProcessStepResponse> submitContacts(
            @PathVariable String processInstanceId,
            @RequestBody ContactsDto contacts,
            @AuthenticationPrincipal Jwt jwt) {
        String camundaUser = userMappingService.mapToCamundaUser(jwt);
        ProcessStepResponse response = dealProcessService.submitContacts(
            processInstanceId, contacts, camundaUser);
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/{processInstanceId}/complete")
    public ResponseEntity<DealCompletionResponse> completeDeal(
            @PathVariable String processInstanceId,
            @AuthenticationPrincipal Jwt jwt) {
        String camundaUser = userMappingService.mapToCamundaUser(jwt);
        DealCompletionResponse response = dealProcessService.completeDeal(
            processInstanceId, camundaUser);
        return ResponseEntity.ok(response);
    }
    
    @GetMapping("/{processInstanceId}/status")
    public ResponseEntity<ProcessStatusResponse> getProcessStatus(
            @PathVariable String processInstanceId,
            @AuthenticationPrincipal Jwt jwt) {
        String camundaUser = userMappingService.mapToCamundaUser(jwt);
        ProcessStatusResponse response = dealProcessService.getProcessStatus(
            processInstanceId, camundaUser);
        return ResponseEntity.ok(response);
    }
}
```

**Step 1.7: Create Deal Process Service**
```java
@Service
public class DealProcessService {
    
    private final CamundaRestClientService camundaClient;
    private static final String PROCESS_DEFINITION_KEY = "deal-initiation-process";
    
    public DealProcessResponse startDealProcess(String camundaUser) {
        // Validate user exists in Camunda (optional)
        // Start process instance
        Map<String, Object> variables = new HashMap<>();
        variables.put("initiatedBy", camundaUser);
        variables.put("initiatedAt", Instant.now().toString());
        
        ProcessInstanceDto processInstance = camundaClient.startProcess(
            PROCESS_DEFINITION_KEY, variables, generateBusinessKey());
        
        // Get first active task
        List<TaskDto> activeTasks = camundaClient.getActiveTasks(
            processInstance.getProcessInstanceKey());
        
        return DealProcessResponse.builder()
            .processInstanceId(processInstance.getProcessInstanceKey())
            .currentStep("deal-info")
            .taskId(activeTasks.get(0).getId())
            .build();
    }
    
    public ProcessStepResponse submitDealInfo(String processInstanceId, 
                                              DealInfoDto dealInfo,
                                              String camundaUser) {
        // Validate process instance belongs to user
        validateProcessOwnership(processInstanceId, camundaUser);
        
        // Get current active task
        List<TaskDto> activeTasks = camundaClient.getActiveTasks(processInstanceId);
        if (activeTasks.isEmpty()) {
            throw new ProcessException("No active task found");
        }
        
        TaskDto currentTask = activeTasks.get(0);
        
        // Update process variables
        Map<String, Object> variables = new HashMap<>();
        variables.put("dealInfo", dealInfo);
        camundaClient.updateProcessVariables(processInstanceId, variables);
        
        // Complete task
        camundaClient.completeTask(currentTask.getId(), variables);
        
        // Get next active task
        List<TaskDto> nextTasks = camundaClient.getActiveTasks(processInstanceId);
        String nextStep = nextTasks.isEmpty() ? "complete" : "parties";
        
        return ProcessStepResponse.builder()
            .processInstanceId(processInstanceId)
            .currentStep(nextStep)
            .taskId(nextTasks.isEmpty() ? null : nextTasks.get(0).getId())
            .build();
    }
    
    // Similar methods for submitParties, submitContacts, completeDeal
    
    private void validateProcessOwnership(String processInstanceId, String camundaUser) {
        // Query process instance and verify initiatedBy variable matches user
        // Throw exception if not authorized
    }
}
```

**2. Frontend Configuration (Angular)**

**Step 2.1: Configure Keycloak Integration**
```typescript
// keycloak-config.ts
export const keycloakConfig = {
  url: 'http://keycloak:8080',
  realm: 'LSCSAD',
  clientId: 'angular-app'
};

// keycloak-init.service.ts
@Injectable({ providedIn: 'root' })
export class KeycloakInitService {
  private keycloak: Keycloak.KeycloakInstance;
  
  init(): Promise<boolean> {
    this.keycloak = new Keycloak(keycloakConfig);
    return this.keycloak.init({
      onLoad: 'check-sso',
      checkLoginIframe: false
    });
  }
  
  getToken(): string | undefined {
    return this.keycloak.token;
  }
}
```

**Step 2.2: Create HTTP Interceptor**
```typescript
// auth.interceptor.ts
@Injectable()
export class AuthInterceptor implements HttpInterceptor {
  constructor(private keycloakService: KeycloakInitService) {}
  
  intercept(req: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    const token = this.keycloakService.getToken();
    if (token) {
      req = req.clone({
        setHeaders: {
          Authorization: `Bearer ${token}`
        }
      });
    }
    return next.handle(req);
  }
}
```

**Step 2.3: Create Deal Service**
```typescript
// deal.service.ts
@Injectable({ providedIn: 'root' })
export class DealService {
  private apiUrl = 'http://localhost:8080/api/deals';
  
  constructor(private http: HttpClient) {}
  
  initiateDeal(): Observable<DealProcessResponse> {
    return this.http.post<DealProcessResponse>(`${this.apiUrl}/initiate`, {});
  }
  
  submitDealInfo(processInstanceId: string, dealInfo: DealInfo): Observable<ProcessStepResponse> {
    return this.http.post<ProcessStepResponse>(
      `${this.apiUrl}/${processInstanceId}/deal-info`, 
      dealInfo
    );
  }
  
  submitParties(processInstanceId: string, parties: Parties): Observable<ProcessStepResponse> {
    return this.http.post<ProcessStepResponse>(
      `${this.apiUrl}/${processInstanceId}/parties`, 
      parties
    );
  }
  
  submitContacts(processInstanceId: string, contacts: Contacts): Observable<ProcessStepResponse> {
    return this.http.post<ProcessStepResponse>(
      `${this.apiUrl}/${processInstanceId}/contacts`, 
      contacts
    );
  }
  
  completeDeal(processInstanceId: string): Observable<DealCompletionResponse> {
    return this.http.post<DealCompletionResponse>(
      `${this.apiUrl}/${processInstanceId}/complete`, 
      {}
    );
  }
  
  getProcessStatus(processInstanceId: string): Observable<ProcessStatusResponse> {
    return this.http.get<ProcessStatusResponse>(
      `${this.apiUrl}/${processInstanceId}/status`
    );
  }
}
```

**Step 2.4: Create Deal Components**
```typescript
// deal-initiate.component.ts
@Component({
  selector: 'app-deal-initiate',
  template: `
    <button (click)="initiateDeal()" [disabled]="loading">
      Initialize Deal
    </button>
  `
})
export class DealInitiateComponent {
  loading = false;
  
  constructor(
    private dealService: DealService,
    private router: Router
  ) {}
  
  initiateDeal() {
    this.loading = true;
    this.dealService.initiateDeal().subscribe({
      next: (response) => {
        this.router.navigate(['/deals', response.processInstanceId, 'deal-info']);
      },
      error: (error) => {
        // Handle error
        this.loading = false;
      }
    });
  }
}

// deal-info.component.ts
@Component({
  selector: 'app-deal-info',
  template: `
    <form [formGroup]="dealInfoForm" (ngSubmit)="onSubmit()">
      <!-- Form fields -->
      <button type="submit" [disabled]="loading">Next</button>
    </form>
  `
})
export class DealInfoComponent implements OnInit {
  dealInfoForm: FormGroup;
  processInstanceId: string;
  loading = false;
  
  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private dealService: DealService,
    private fb: FormBuilder
  ) {}
  
  ngOnInit() {
    this.processInstanceId = this.route.snapshot.paramMap.get('processInstanceId')!;
    this.dealInfoForm = this.fb.group({
      dealName: ['', Validators.required],
      dealType: ['', Validators.required],
      // ... other fields
    });
  }
  
  onSubmit() {
    if (this.dealInfoForm.valid) {
      this.loading = true;
      this.dealService.submitDealInfo(
        this.processInstanceId, 
        this.dealInfoForm.value
      ).subscribe({
        next: (response) => {
          this.router.navigate(['/deals', this.processInstanceId, 'parties']);
        },
        error: (error) => {
          // Handle error
          this.loading = false;
        }
      });
    }
  }
}
```

**Step 2.5: Configure Routes**
```typescript
// app-routing.module.ts
const routes: Routes = [
  {
    path: 'deals/initiate',
    component: DealInitiateComponent,
    canActivate: [AuthGuard]
  },
  {
    path: 'deals/:processInstanceId/deal-info',
    component: DealInfoComponent,
    canActivate: [AuthGuard]
  },
  {
    path: 'deals/:processInstanceId/parties',
    component: PartiesComponent,
    canActivate: [AuthGuard]
  },
  {
    path: 'deals/:processInstanceId/contacts',
    component: ContactsComponent,
    canActivate: [AuthGuard]
  },
  {
    path: 'deals/:processInstanceId/complete',
    component: CompleteComponent,
    canActivate: [AuthGuard]
  }
];
```

**3. Camunda BPMN Process Design**

**Process Definition:**
- Process Definition Key: `deal-initiation-process`
- Process Variables:
  - `initiatedBy` (String)
  - `initiatedAt` (String)
  - `dealInfo` (JSON)
  - `parties` (JSON)
  - `contacts` (JSON)

**BPMN Structure:**
```
Start Event
  ↓
User Task: "Enter Deal Info"
  ↓
User Task: "Add Parties"
  ↓
User Task: "Add Contacts"
  ↓
Service Task: "Validate and Complete"
  ↓
End Event
```

**Task Configuration:**
- Each User Task has form key matching frontend route
- Form key: `deal-info`, `parties`, `contacts`
- Task assignment: Use process variable `initiatedBy`

---

### Sample API/Process Flow

**Complete Flow Diagram:**

```
1. User Authentication
   ┌─────────┐
   │ Angular │ ──(Login)──▶ Keycloak (LSCSAD realm)
   └─────────┘              │
                            │ Returns JWT Token
                            ▼
                        ┌─────────┐
                        │ Angular │ (Stores token)
                        └─────────┘

2. Initialize Deal Process
   ┌─────────┐
   │ Angular │ ──POST /api/deals/initiate
   │         │    Headers: Authorization: Bearer <LSCSAD_TOKEN>
   └─────────┘    │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │
            │   Backend    │
            └──────────────┘
                  │
                  │ 1. Validate LSCSAD token
                  │ 2. Extract user identity
                  │ 3. Map to Camunda user
                  │
                  ▼
            ┌──────────────┐
            │   Keycloak   │ ──Client Credentials Grant──▶
            │   Service    │    (camunda-platform realm)
            │   Account    │
            └──────────────┘
                  │
                  │ Returns Camunda realm token
                  ▼
            ┌──────────────┐
            │ Spring Boot  │
            │   Backend    │
            └──────────────┘
                  │
                  │ POST /v1/process-instances
                  │ Headers: Authorization: Bearer <CAMUNDA_TOKEN>
                  │ Body: { processDefinitionKey, variables, businessKey }
                  ▼
            ┌──────────────┐
            │   Camunda 8  │
            │   REST API   │
            └──────────────┘
                  │
                  │ Returns ProcessInstanceDto
                  ▼
            ┌──────────────┐
            │ Spring Boot  │ ──Returns DealProcessResponse──▶
            │   Backend    │    { processInstanceId, currentStep, taskId }
            └──────────────┘
                  │
                  ▼
            ┌─────────┐
            │ Angular │ ──Navigate to /deals/{processInstanceId}/deal-info
            └─────────┘

3. Submit Deal Info Form
   ┌─────────┐
   │ Angular │ ──POST /api/deals/{processInstanceId}/deal-info
   │         │    Headers: Authorization: Bearer <LSCSAD_TOKEN>
   │         │    Body: { dealName, dealType, ... }
   └─────────┘    │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │
            │   Backend    │
            └──────────────┘
                  │
                  │ 1. Validate LSCSAD token
                  │ 2. Validate process ownership
                  │ 3. Get Camunda token
                  │ 4. Update process variables
                  │ 5. Complete current task
                  │ 6. Get next active task
                  │
                  ▼
            ┌──────────────┐
            │   Camunda 8  │
            │   REST API   │
            └──────────────┘
                  │
                  │ Process advances to next task
                  ▼
            ┌──────────────┐
            │ Spring Boot  │ ──Returns ProcessStepResponse──▶
            │   Backend    │    { processInstanceId, currentStep: "parties", taskId }
            └──────────────┘
                  │
                  ▼
            ┌─────────┐
            │ Angular │ ──Navigate to /deals/{processInstanceId}/parties
            └─────────┘

4. Submit Parties Form (Similar to Step 3)
   ┌─────────┐
   │ Angular │ ──POST /api/deals/{processInstanceId}/parties
   │         │    Body: { parties: [...] }
   └─────────┘    │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │ ──Update variables, complete task──▶
            │   Backend    │
            └──────────────┘
                  │
                  ▼
            ┌──────────────┐
            │   Camunda 8  │
            │   REST API   │
            └──────────────┘
                  │
                  ▼
            ┌─────────┐
            │ Angular │ ──Navigate to /deals/{processInstanceId}/contacts
            └─────────┘

5. Submit Contacts Form (Similar to Step 3)
   ┌─────────┐
   │ Angular │ ──POST /api/deals/{processInstanceId}/contacts
   │         │    Body: { contacts: [...] }
   └─────────┘    │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │ ──Update variables, complete task──▶
            │   Backend    │
            └──────────────┘
                  │
                  ▼
            ┌──────────────┐
            │   Camunda 8  │
            │   REST API   │
            └──────────────┘
                  │
                  ▼
            ┌─────────┐
            │ Angular │ ──Navigate to /deals/{processInstanceId}/complete
            └─────────┘

6. Complete Deal Process
   ┌─────────┐
   │ Angular │ ──POST /api/deals/{processInstanceId}/complete
   └─────────┘    │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │ ──Complete final task──▶
            │   Backend    │
            └──────────────┘
                  │
                  ▼
            ┌──────────────┐
            │   Camunda 8  │ ──Process instance completes──▶
            │   REST API   │
            └──────────────┘
                  │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │ ──Returns DealCompletionResponse──▶
            │   Backend    │    { success: true, summary: {...} }
            └──────────────┘
                  │
                  ▼
            ┌─────────┐
            │ Angular │ ──Show success message, redirect to dashboard
            └─────────┘
```

---

### Key Implementation Considerations

**1. User Identity Mapping:**
- **Strategy:** Use email address as primary mapping identifier (most reliable)
- **Fallback:** Use `preferred_username` if email not available
- **Implementation:** Create mapping service that extracts identity from JWT claims
- **Edge Cases:** Handle users that don't exist in Camunda realm (create user or use service account identity)
- **Security:** Validate mapping consistency, prevent identity spoofing

**2. Token Management:**
- **Camunda Realm Token:** Store in backend only, never expose to frontend
- **Caching:** Cache Camunda realm access tokens with TTL (typically 5 minutes before expiry)
- **Refresh:** Implement automatic token refresh before expiration
- **Storage:** Store client credentials securely (environment variables, Kubernetes secrets, HashiCorp Vault)
- **Rotation:** Implement credential rotation strategy

**3. Process State Management:**
- **Tracking:** Use process instance ID as primary identifier
- **Validation:** Always validate process instance ownership before operations
- **State Queries:** Query active tasks to determine current step
- **Error Handling:** Handle process instance not found, already completed, or invalid state
- **Recovery:** Provide mechanism to recover from invalid states (restart process or manual intervention)

**4. Error Handling:**
- **Frontend:** Display user-friendly error messages
- **Backend:** Log detailed errors for debugging, return generic messages to frontend
- **Camunda Errors:** Map Camunda-specific errors to user-friendly messages
- **Network Errors:** Implement retry logic with exponential backoff
- **Validation Errors:** Return field-level validation errors for form display

**5. Security:**
- **Authentication:** Validate all tokens (signature, expiration, issuer)
- **Authorization:** Enforce process instance ownership checks
- **Input Validation:** Validate and sanitize all input
- **Rate Limiting:** Implement rate limiting on all endpoints
- **CORS:** Configure CORS to allow only trusted origins
- **Audit Logging:** Log all process operations with user identity

**6. Performance:**
- **Token Caching:** Cache Camunda realm tokens to reduce Keycloak load
- **Request Batching:** Batch multiple Camunda API calls when possible
- **Connection Pooling:** Use connection pooling for HTTP clients
- **Async Operations:** Use async/await or reactive programming for non-blocking operations

**7. Monitoring and Observability:**
- **Logging:** Log all process operations, API calls, and errors
- **Metrics:** Track process instance creation, completion rates, error rates
- **Tracing:** Implement distributed tracing for request flow
- **Alerts:** Set up alerts for high error rates, failed authentications

**8. Testing:**
- **Unit Tests:** Test authentication, mapping, and service logic
- **Integration Tests:** Test end-to-end flow with test Keycloak and Camunda instances
- **Security Tests:** Test authentication, authorization, and input validation
- **Load Tests:** Test performance under load

---

## Conclusion

This integration approach provides a secure, maintainable, and scalable solution for connecting Angular front-end, Spring Boot backend, and Camunda 8.8 with cross-realm Keycloak authentication. The backend proxy pattern ensures security by never exposing Camunda realm credentials to the frontend, while the service account pattern provides reliable authentication to Camunda APIs. User identity mapping enables seamless user experience while maintaining proper access control.

The recommended architecture follows industry best practices for microservices security, API design, and process orchestration, ensuring a production-ready implementation that can scale and evolve with business requirements.
