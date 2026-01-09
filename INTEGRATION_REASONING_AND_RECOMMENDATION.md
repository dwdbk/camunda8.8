# Camunda 8.8 Integration: Reasoning and Recommendation

## Reasoning

### 1. Authentication Bridging

**Problem Analysis:**
- Users authenticate to Angular application via Keycloak realm `LSCSAD` and receive JWT tokens scoped to that realm
- Camunda 8.8 REST API requires authentication via Keycloak realm `camunda-platform`
- These are separate realms with separate user stores, token issuers, and client configurations
- A token issued by `LSCSAD` realm cannot directly authenticate requests to Camunda APIs protected by `camunda-platform` realm (different issuer, different audience)

**Authentication Strategy Options:**

**Option A: Direct Token Exchange (Not Viable)**
- Frontend attempts to exchange `LSCSAD` token for `camunda-platform` token
- Requires user to authenticate twice (poor UX)
- Security risk: exposes Camunda realm client credentials to frontend
- Complex token management in browser storage (two tokens, refresh logic)
- **Verdict:** Not viable for production due to security and UX concerns

**Option B: Service Account Pattern (Recommended)**
- Backend uses a service account with client credentials grant to authenticate with Camunda realm
- Backend maps authenticated `LSCSAD` user identity to Camunda user identity
- User identity mapping strategies:
  - **Email address** (most reliable if same email exists in both realms)
  - **Username** (`preferred_username` claim)
  - **Subject claim** (`sub`) with external mapping table
  - **Custom claim** (e.g., `employee_id`, `external_id`)
- Backend maintains Camunda realm credentials securely (environment variables, secrets management)
- Camunda realm tokens never exposed to frontend
- **Verdict:** Secure, maintainable, scalable, production-ready

**Option C: Token Exchange via Keycloak Identity Provider (Alternative)**
- Use Keycloak's Token Exchange feature to exchange tokens between realms
- Configure Identity Provider in Keycloak to link `camunda-platform` realm to `LSCSAD` realm
- Backend exchanges `LSCSAD` token for `camunda-platform` token using Keycloak Token Exchange API
- Requires Keycloak Token Exchange feature enabled and properly configured
- Provides user-level authentication in Camunda (better audit trail than service account)
- **Pros:**
  - User identity preserved in Camunda (not service account)
  - Better audit trail (operations tied to actual user)
  - Native Keycloak feature (no custom token management)
- **Cons:**
  - Requires Keycloak Token Exchange feature (available in Keycloak 7.0+)
  - More complex Keycloak configuration
  - Requires users to exist in both realms (or federated identity)
- **Verdict:** Good alternative for enterprise scenarios requiring user-level audit logs

**Option D: Token Delegation/Impersonation**
- Backend validates `LSCSAD` token
- Backend impersonates user in Camunda realm using service account with delegation rights
- Requires Keycloak token exchange or impersonation features
- More complex setup but provides user-level audit trail in Camunda
- **Verdict:** Good for enterprise scenarios requiring detailed audit logs, but adds complexity

**Recommended Approach:**
Use **Option B (Service Account Pattern)** for simplicity, or **Option C (Token Exchange)** if user-level audit trail is required. Implementation steps:
1. Backend validates incoming `LSCSAD` realm JWT token from Angular (standard Spring Security OAuth2 Resource Server)
2. Backend extracts user identity from token claims (email, username, or sub)
3. Backend uses service account credentials (client ID + secret) to obtain `camunda-platform` realm token via client credentials grant
4. Backend maps user identity when creating/querying process instances (sets `initiatedBy` variable)
5. All Camunda operations are performed on behalf of the authenticated user, with proper audit trail

**Token Exchange Implementation (Option C):**

**Keycloak Configuration:**
1. Enable Token Exchange feature in Keycloak (available in Keycloak 7.0+, enabled by default in Keycloak 25.0.6)
2. Configure Identity Provider in `camunda-platform` realm:
   - Add `LSCSAD` realm as Identity Provider
   - Configure token exchange permissions
   - Map user attributes between realms
3. Grant token exchange permissions to backend client in `camunda-platform` realm

**Backend Implementation:**
```java
@Service
public class CamundaTokenExchangeService {
    
    public String exchangeToken(String lscsadToken) {
        // Exchange LSCSAD token for camunda-platform token
        TokenExchangeRequest request = TokenExchangeRequest.builder()
            .subjectToken(lscsadToken)
            .subjectTokenType("urn:ietf:params:oauth:token-type:access_token")
            .requestedTokenType("urn:ietf:params:oauth:token-type:access_token")
            .audience("camunda-platform-client")
            .build();
        
        // Call Keycloak Token Exchange endpoint
        TokenExchangeResponse response = webClient.post()
            .uri(keycloakUrl + "/realms/camunda-platform/protocol/openid-connect/token")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(TokenExchangeResponse.class)
            .block();
        
        return response.getAccessToken();
    }
}
```

**Implementation Considerations:**
- Store Camunda realm client credentials securely (environment variables, Kubernetes secrets, HashiCorp Vault)
- Implement user identity mapping service with fallback strategies
- Handle edge cases: user doesn't exist in Camunda realm (create user, use service account identity, or reject)
- Cache Camunda realm access tokens with appropriate TTL (typically 5 minutes, refresh 1 minute before expiry)
- Implement token refresh mechanism for long-running sessions
- Log all authentication attempts and token refreshes for security auditing
- **For Token Exchange:** Configure Keycloak Identity Provider properly, handle token exchange failures gracefully

---

### 1.1. Reverse Authentication: Camunda Calling Application Backends

**Problem Analysis:**
- Camunda 8.8 process instances may need to call back to application backends (e.g., update deal status after process completion)
- Application backends are protected by `LSCSAD` realm authentication
- Camunda service tasks or HTTP connectors need to authenticate with `LSCSAD` realm
- This is the reverse direction: Camunda → Application Backend (vs. Application Backend → Camunda)

**Authentication Strategies for Camunda → Backend:**

**Option A: Service Account Pattern (Recommended)**
- Create service account client in `LSCSAD` realm for Camunda
- Camunda uses client credentials grant to obtain `LSCSAD` realm token
- Configure Camunda HTTP connector or external task worker with service account credentials
- **Pros:**
  - Simple to implement
  - Secure (credentials stored in Camunda configuration)
  - No user context needed (service-to-service communication)
- **Cons:**
  - Operations not tied to specific user (service account identity)
  - Less granular audit trail
- **Verdict:** Recommended for service-to-service calls where user context is not critical

**Option B: Token Exchange Pattern**
- Camunda exchanges `camunda-platform` realm token for `LSCSAD` realm token
- Requires Keycloak Token Exchange configuration between realms
- Preserves user context in backend calls
- **Pros:**
  - User identity preserved in backend calls
  - Better audit trail
  - Operations tied to actual user
- **Cons:**
  - More complex configuration
  - Requires Token Exchange feature
- **Verdict:** Recommended when user context is required in backend operations

**Option C: Process Variable Token Passing**
- Store `LSCSAD` token in process variables (passed from initial request)
- Use token in HTTP connector calls
- **Pros:**
  - Simple implementation
  - Preserves user context
- **Cons:**
  - **Security Risk:** Tokens stored in process variables (may expire, security concern)
  - Token expiration handling complex
  - Not recommended for long-running processes
- **Verdict:** Not recommended due to security and token expiration concerns

**Recommended Approach:**
Use **Option A (Service Account Pattern)** for most cases, or **Option B (Token Exchange)** when user context is required.

**Implementation Example:**

**Camunda HTTP Connector Configuration:**
```json
{
  "type": "io.camunda:http:1",
  "inputParameters": {
    "url": "https://api.example.com/deals/{dealId}/status",
    "method": "PATCH",
    "headers": {
      "Authorization": "Bearer ${camundaLscsadToken}"
    },
    "body": {
      "status": "COMPLETED",
      "processInstanceId": "${processInstanceKey}"
    }
  }
}
```

**Backend Service Account Configuration:**
```yaml
camunda:
  lscsad:
    keycloak-url: http://keycloak:8080
    realm: LSCSAD
    client-id: ${CAMUNDA_LSCSAD_CLIENT_ID}
    client-secret: ${CAMUNDA_LSCSAD_CLIENT_SECRET}
```

**External Task Worker Pattern:**
```java
@ZeebeWorker(type = "update-deal-status")
public void updateDealStatus(JobClient client, ActivatedJob job) {
    // Get LSCSAD token (service account or token exchange)
    String lscsadToken = getLscsadToken();
    
    // Call backend API
    webClient.patch()
        .uri("https://api.example.com/deals/{dealId}/status", dealId)
        .header("Authorization", "Bearer " + lscsadToken)
        .bodyValue(updateRequest)
        .retrieve()
        .bodyToMono(Void.class)
        .block();
    
    client.newCompleteCommand(job.getKey()).send();
}
```

**Use Cases:**
- **After Process Completion:** Update deal status in application database
- **After Validation:** Notify external systems of deal approval
- **After Task Completion:** Update related entities in application
- **Error Handling:** Rollback operations in application on process failure

---

### 2. API Access Pattern

**Problem Statement:**
Determine whether Angular should call Camunda APIs directly or route all requests through the Spring Boot backend.

**Option A: Frontend Direct API Calls**
**Pros:**
- Reduced backend load (direct client-to-Camunda communication)
- Lower latency (fewer network hops)
- Simpler backend code (no proxy layer)
- Direct access to Camunda REST API features

**Cons:**
- **Critical Security Issue:** Requires exposing Camunda realm tokens to frontend (major security risk)
- Frontend must handle Camunda authentication complexity (token refresh, error handling)
- Cross-origin issues (CORS configuration required on Camunda)
- No centralized business logic or validation layer
- Difficult to implement cross-realm authentication (would require token exchange in browser)
- Frontend becomes tightly coupled to Camunda API structure (harder to evolve)
- Harder to implement rate limiting, caching, or request transformation
- No centralized error handling or user-friendly error messages
- **Verdict:** Not recommended due to security vulnerabilities and architectural concerns

**Option B: Backend Proxy Pattern (Recommended)**
**Pros:**
- **Security:** Camunda realm credentials never exposed to frontend
- Centralized authentication and authorization logic
- Backend can implement business rules, validation, and data transformation
- Easier to implement user identity mapping
- Single point of control for Camunda API access
- Can implement caching, rate limiting, and request aggregation
- Easier to handle errors and provide user-friendly messages
- Backend can implement audit logging and monitoring
- Frontend remains decoupled from Camunda API details
- Easier to add features like request queuing, retry logic, circuit breakers
- **Verdict:** Strongly recommended for production

**Cons:**
- Additional network hop (minimal impact in most scenarios, typically <10ms)
- Backend must implement proxy endpoints (standard REST controller pattern)
- Slightly more complex backend code (but more maintainable long-term)

**Option C: Hybrid Approach**
- Frontend calls Camunda APIs directly for read-only operations (queries, process definitions)
- Backend handles all write operations (start process, complete tasks, update variables)
- **Verdict:** Possible but adds complexity and security concerns; not recommended unless specific performance requirements justify it

**Option D: GraphQL with Netflix DGS (Your Current Architecture)**
- Frontend uses GraphQL (Netflix DGS) to communicate with backend
- Backend uses REST to communicate with Camunda 8.8
- GraphQL provides unified API layer, Camunda remains REST-based
- **Pros:**
  - Leverages existing GraphQL infrastructure
  - Single API endpoint for frontend (GraphQL)
  - Type-safe queries and mutations
  - Flexible data fetching (fetch only needed fields)
  - Backend abstracts Camunda REST API complexity
- **Cons:**
  - Camunda 8.8 does not natively support GraphQL (REST API only)
  - Backend must translate GraphQL to Camunda REST calls
  - Additional abstraction layer
- **Verdict:** Recommended if already using GraphQL - backend acts as GraphQL-to-REST adapter

**GraphQL Integration Pattern:**
```
Frontend (Angular) 
  → GraphQL Query/Mutation (Netflix DGS)
  → Spring Boot Backend (GraphQL Resolvers)
  → Camunda REST API (via CamundaRestClientService)
  → Camunda 8.8 Engine
```

**GraphQL Schema Example:**
```graphql
type DealProcess {
  processInstanceId: ID!
  currentStep: String!
  state: ProcessState!
  dealInfo: DealInfo
  parties: [Party!]
  contacts: [Contact!]
}

type Mutation {
  initiateDeal: DealProcess!
  submitDealInfo(processInstanceId: ID!, dealInfo: DealInfoInput!): ProcessStep!
  submitParties(processInstanceId: ID!, parties: [PartyInput!]!): ProcessStep!
  submitContacts(processInstanceId: ID!, contacts: [ContactInput!]!): ProcessStep!
  completeDeal(processInstanceId: ID!): DealCompletion!
}

type Query {
  dealProcess(processInstanceId: ID!): DealProcess
  dealProcessStatus(processInstanceId: ID!): ProcessStatus
}
```

**Netflix DGS Resolver Implementation:**
```java
@DgsComponent
public class DealProcessDataFetcher {
    
    private final DealProcessService dealProcessService;
    
    @DgsMutation
    public DealProcess initiateDeal(DgsDataFetchingEnvironment dfe) {
        String userId = extractUserId(dfe); // From security context
        return dealProcessService.startDealProcess(userId);
    }
    
    @DgsMutation
    public ProcessStep submitDealInfo(
            @InputArgument String processInstanceId,
            @InputArgument DealInfoInput dealInfo,
            DgsDataFetchingEnvironment dfe) {
        String userId = extractUserId(dfe);
        return dealProcessService.submitDealInfo(processInstanceId, dealInfo, userId);
    }
    
    // Similar for other mutations
}
```

**Note on Direct GraphQL to Camunda:**
- **Camunda 8.8 does not support GraphQL natively** - it only provides REST API
- You cannot use GraphQL directly with Camunda 8.8
- **Solution:** Use GraphQL between frontend and backend, REST between backend and Camunda
- Backend acts as GraphQL-to-REST adapter layer

**Recommended Approach:**
Use **Option B (Backend Proxy Pattern)** with **GraphQL (Netflix DGS)** for frontend-backend communication, and REST for backend-Camunda communication. This provides:
- **Security:** Camunda realm tokens never leave the backend
- **Maintainability:** Single source of truth for Camunda integration logic
- **Flexibility:** Easy to change Camunda API usage without frontend changes
- **User Experience:** Backend can transform Camunda errors into user-friendly messages
- **Observability:** Centralized logging, monitoring, and tracing

**API Endpoint Structure:**

**REST API (Alternative):**
```
Frontend → Backend Endpoints:
- POST /api/deals/initiate              → Start Camunda process instance
- GET  /api/deals/{processInstanceId}/status → Get process status and current step
- POST /api/deals/{processInstanceId}/deal-info → Submit deal info form, advance process
- POST /api/deals/{processInstanceId}/parties → Submit parties form, advance process
- POST /api/deals/{processInstanceId}/contacts → Submit contacts form, advance process
- POST /api/deals/{processInstanceId}/complete → Complete process, finalize deal
- GET  /api/deals/{processInstanceId}/current-step → Get current step information
```

**GraphQL API (Recommended for Your Architecture):**
```
Frontend → GraphQL Endpoint (Netflix DGS):
- Mutation: initiateDeal → Start Camunda process instance
- Query: dealProcess(processInstanceId) → Get process details
- Query: dealProcessStatus(processInstanceId) → Get process status
- Mutation: submitDealInfo(processInstanceId, dealInfo) → Submit deal info form
- Mutation: submitParties(processInstanceId, parties) → Submit parties form
- Mutation: submitContacts(processInstanceId, contacts) → Submit contacts form
- Mutation: completeDeal(processInstanceId) → Complete process

Backend → Camunda REST API:
- POST /v1/process-instances → Start process
- GET /v1/process-instances/{key} → Get process instance
- GET /v1/tasks?processInstanceKey={key} → Get active tasks
- POST /v1/tasks/{key}/complete → Complete task
- PATCH /v1/process-instances/{key}/variables → Update variables
```

---

### 3. Process Lifecycle Management

**Problem Statement:**
Correlate frontend user actions (form submissions, navigation) with Camunda process instance state and task completion. Ensure data persistence and process state synchronization.

**Process Flow Design:**

**Step 1: Process Initialization**
- User clicks "Initialize Deal" button in Angular
- Frontend calls `POST /api/deals/initiate` with empty body
- Backend:
  - Validates user authentication (LSCSAD token)
  - Extracts user identity from JWT claims
  - Maps user identity to Camunda user identifier
  - Obtains Camunda realm access token (service account)
  - Starts Camunda process instance using REST API (`POST /v1/process-instances`)
  - Sets initial process variables: `initiatedBy` (user identifier), `initiatedAt` (timestamp), `businessKey` (generated)
  - Queries active tasks for the process instance (`GET /v1/tasks?processInstanceKey={key}`)
  - Returns `DealProcessResponse` with: `processInstanceId`, `currentStep` ("deal-info"), `taskId`
- Frontend stores process instance ID (session storage or state management)
- Frontend navigates to `/deals/{processInstanceId}/deal-info` route

**Step 2: Deal Info Form Submission**
- User fills and submits deal info form
- Frontend calls `POST /api/deals/{processInstanceId}/deal-info` with form data (`DealInfo` DTO)
- Backend:
  - Validates process instance exists and belongs to user (checks `initiatedBy` variable)
  - Gets current active task for process instance
  - Updates Camunda process variables with deal info (`dealInfo` variable as JSON)
  - Completes current user task (`POST /v1/tasks/{taskKey}/complete`)
  - Process advances to next step (BPMN flow)
  - Retrieves next active task (or determines process completed)
  - Returns `ProcessStepResponse` with: `processInstanceId`, `currentStep` ("parties"), `taskId`
- Frontend navigates to `/deals/{processInstanceId}/parties` route

**Step 3: Parties Form Submission**
- Similar to Step 2, but for parties data
- Backend updates `parties` process variable and completes task
- Process advances to contacts step
- Frontend navigates to `/deals/{processInstanceId}/contacts` route

**Step 4: Contacts Form Submission**
- Similar to Step 2, but for contacts data
- Backend updates `contacts` process variable and completes task
- Process advances to completion step
- Frontend navigates to `/deals/{processInstanceId}/complete` route

**Step 5: Process Completion**
- User clicks "Complete" button
- Frontend calls `POST /api/deals/{processInstanceId}/complete`
- Backend:
  - Validates all required data is present (dealInfo, parties, contacts)
  - Completes final task
  - Process instance completes (reaches end event)
  - Returns `DealCompletionResponse` with: `success`, `processInstanceId`, `summary` (deal name, parties count, contacts count)
- Frontend shows success message and redirects to deal list/dashboard

**Process State Tracking:**

**Option A: User Tasks (Recommended)**
- Each step is a User Task in Camunda BPMN process
- Backend queries active tasks for process instance to determine current step
- Frontend displays form based on current active task (form key or task definition ID)
- Form submission completes the task, advancing process to next step
- **Pros:** 
  - Native Camunda task management
  - Built-in task assignment and delegation
  - Complete audit trail (who completed which task, when)
  - Task history and reporting capabilities
- **Cons:** 
  - Requires task querying on each step
  - Slightly more complex than process variables

**Option B: Process Variables + Service Tasks**
- Process uses service tasks with process variables
- Frontend tracks step via process variable (e.g., `currentStep: "deal-info"`)
- Backend updates process variables and triggers process advancement via message events or service tasks
- **Pros:** 
  - Simpler task management
  - Direct variable control
  - Less overhead (no task queries)
- **Cons:** 
  - Less native Camunda task features
  - Manual step tracking
  - Weaker audit trail

**Option C: Message Events**
- Process uses message events to advance between steps
- Frontend sends messages via backend
- Backend correlates messages to process instance
- **Pros:** 
  - Decoupled, event-driven architecture
  - Flexible process flow
- **Cons:** 
  - More complex implementation
  - Requires message correlation logic
  - Harder to track current step

**Recommended Approach:**
Use **Option A (User Tasks)** for better integration with Camunda's task management and audit capabilities. Each form step corresponds to a User Task in the BPMN process.

**Process Variable Strategy:**
- Store form data as process variables:
  - `dealInfo` (JSON object with deal information)
  - `parties` (JSON array of party objects)
  - `contacts` (JSON array of contact objects)
- Include metadata variables:
  - `initiatedBy` (String - user identifier)
  - `initiatedAt` (String - ISO timestamp)
  - `lastUpdatedAt` (String - ISO timestamp)
  - `businessKey` (String - external correlation key, e.g., "DEAL-2024-001")
- Use consistent naming conventions for all variables
- Validate variable types and required fields before process completion

**Error Handling:**
- **Process instance not found:** Return 404, allow user to restart process
- **Process already completed:** Return appropriate message, prevent further updates
- **Task not found:** Return error, allow user to refresh or restart
- **Validation fails:** Return validation errors with field-level details, allow user to correct and resubmit
- **Network errors:** Implement retry logic with exponential backoff, show user-friendly error messages
- **Concurrent modifications:** Implement optimistic locking or version checks

---

### 4. User Experience

**Problem Statement:**
Design frontend navigation and form flow that stays synchronized with Camunda process state. Handle browser refresh, back navigation, and error recovery gracefully.

**Navigation Flow:**

**Initial State:**
- User is on dashboard/deal list page
- "Initialize Deal" button is visible
- User is authenticated (LSCSAD token present)

**After Process Start:**
- User is redirected to `/deals/{processInstanceId}/deal-info`
- Form is pre-populated if returning user (load from process variables)
- Process instance ID stored in application state (service or NgRx store)
- URL contains process instance ID (bookmarkable, shareable)

**Form Submission Flow:**
1. User submits form
2. Frontend shows loading indicator (disable submit button, show spinner)
3. Frontend sends POST request to backend with form data
4. Backend processes request (validates, updates Camunda, advances process)
5. **On success:**
   - Frontend receives next step information (`ProcessStepResponse`)
   - Frontend navigates to next form route (e.g., `/deals/{processInstanceId}/parties`)
   - Form data is cleared (or pre-populated if editing)
   - Loading indicator hidden
6. **On error:**
   - Frontend displays error message (toast notification or inline)
   - User can correct form data and resubmit
   - Process state remains unchanged (no process advancement)
   - Loading indicator hidden

**Route Structure:**
```
/deals/initiate                    → Initialize new deal (button click)
/deals/:processInstanceId/deal-info → Deal information form (Step 1)
/deals/:processInstanceId/parties   → Parties form (Step 2)
/deals/:processInstanceId/contacts  → Contacts form (Step 3)
/deals/:processInstanceId/complete  → Completion confirmation (Step 4)
/deals/:processInstanceId/summary   → Deal summary (after completion)
```

**State Management:**

**Option A: Route-Based State**
- Process instance ID in URL (path parameter)
- Each route loads current step from backend on component initialization
- Backend validates step and returns appropriate form data
- **Pros:** 
  - Bookmarkable URLs
  - Browser back/forward works correctly
  - Direct URL access works
  - Shareable links
- **Cons:** 
  - Requires backend validation on each route load
  - Additional API call on route change

**Option B: Application State Management**
- Store process instance ID and current step in application state (NgRx, Redux, or service)
- Routes are determined by state
- **Pros:** 
  - Faster navigation (no backend call)
  - Less backend load
- **Cons:** 
  - State can become stale
  - Requires synchronization with backend
  - Browser refresh loses state
  - Not bookmarkable

**Recommended Approach:**
Use **Hybrid Approach:**
- Process instance ID in URL (for bookmarking and direct access)
- On route load, validate with backend and get current step (`GET /api/deals/{id}/status`)
- If user tries to access wrong step (e.g., skipping ahead), redirect to correct step
- Store minimal state in application (process instance ID, current step) for faster navigation
- Always validate step with backend before form submission
- Handle browser refresh gracefully (reload from backend)

**Form Persistence:**
- Optionally save form data to local storage as user types (auto-save feature)
- On form load, check for saved data and pre-populate form
- Clear saved data on successful submission
- Handle browser refresh gracefully (restore from local storage, then sync with backend)

**Loading States:**
- Show loading spinner during API calls
- Disable form submission during processing
- Provide clear feedback on success/error (toast notifications)
- Show progress indicator for multi-step process (e.g., "Step 2 of 4")

**Error Recovery:**
- **Process instance not found:** Offer to start new process, show error message
- **Process completed:** Show summary, prevent further edits, offer to view details
- **Validation errors:** Highlight invalid fields, show specific error messages, allow correction
- **Network errors:** Show retry option, implement automatic retry with exponential backoff
- **Concurrent access:** Detect and handle conflicts, show warning message

**User Feedback:**
- Success messages after each step completion
- Progress indicator showing current step (e.g., "Step 2 of 4: Add Parties")
- Clear error messages with actionable guidance
- Confirmation dialogs for critical actions (e.g., process completion)

---

### 5. Security

**Problem Statement:**
Ensure secure authentication, authorization, and data protection across the integration. Prevent unauthorized access, data leakage, and privilege escalation.

**Security Requirements:**

**1. Authentication Security:**

**Frontend → Backend:**
- All requests include `LSCSAD` realm JWT token in `Authorization` header
- Backend validates token signature, expiration, and issuer using Spring Security OAuth2 Resource Server
- Backend extracts user identity from token claims (`email`, `preferred_username`, `sub`)
- Token validation includes:
  - Signature verification (using JWK set from Keycloak)
  - Expiration check
  - Issuer validation (must be LSCSAD realm)
  - Audience validation (if configured)

**Backend → Camunda:**
- Backend uses service account credentials (client ID + secret) for Camunda realm
- Credentials stored securely (environment variables, Kubernetes secrets, HashiCorp Vault)
- Never expose Camunda realm tokens to frontend
- Implement token caching with TTL (typically 5 minutes, refresh 1 minute before expiry)
- Implement token refresh before expiration
- Handle token refresh failures gracefully (retry, alert, fallback)

**2. Authorization Security:**

**User Identity Mapping:**
- Map `LSCSAD` user to Camunda user consistently
- Use immutable identifier (email, username, or custom claim)
- Validate mapping exists before process operations
- Handle cases where user doesn't exist in Camunda realm:
  - Option 1: Create user in Camunda realm (if user sync enabled)
  - Option 2: Use service account identity with user identifier in process variables
  - Option 3: Reject request with appropriate error message

**Process Instance Access Control:**
- Backend validates user owns/created process instance before operations
- Query process instances filtered by user identity (`initiatedBy` variable)
- Prevent users from accessing other users' process instances
- Implement role-based access if needed (e.g., managers can view all deals)
- Validate process instance ownership on every operation:
  - Get process instance variables
  - Check `initiatedBy` matches authenticated user
  - Throw `403 Forbidden` if not authorized

**3. Data Protection:**

**In Transit:**
- Use HTTPS/TLS for all communications (frontend → backend → Camunda)
- Validate SSL certificates (prevent MITM attacks)
- Use secure headers (HSTS, CSP, X-Frame-Options)
- Implement certificate pinning if required

**At Rest:**
- Encrypt sensitive process variables if required (PII, financial data)
- Follow data retention policies (archive or delete old process instances)
- Implement data masking for sensitive fields in logs
- Secure storage of Camunda realm credentials

**4. API Security:**

**Rate Limiting:**
- Implement rate limiting on backend endpoints
- Prevent abuse and DoS attacks
- Use token bucket or sliding window algorithm
- Return `429 Too Many Requests` when limit exceeded

**Input Validation:**
- Validate all input from frontend (type, format, length, range)
- Sanitize data before storing in process variables
- Prevent injection attacks (SQL, NoSQL, XSS)
- Use DTOs with validation annotations (`@Valid`, `@NotNull`, `@Size`)
- Reject malformed JSON, oversized payloads

**CORS Configuration:**
- Configure CORS to allow only trusted origins
- Use `credentials: true` for cookie-based auth (if used)
- Restrict allowed methods (`GET`, `POST`, `PATCH`)
- Restrict allowed headers (`Authorization`, `Content-Type`)
- Set appropriate `Access-Control-Max-Age` for preflight caching

**5. Audit and Logging:**
- Log all process operations with user identity
- Include process instance ID, operation type, timestamp
- Store logs securely, implement log rotation
- Enable audit trail in Camunda for compliance
- Log authentication attempts (success and failure)
- Log authorization failures (403 errors)
- Implement log aggregation and monitoring (ELK stack, Splunk)

**6. Error Handling Security:**
- Don't expose internal errors or stack traces to frontend
- Don't leak information about process instances, users, or system structure
- Return generic error messages to frontend (e.g., "An error occurred")
- Log detailed errors server-side for debugging
- Implement error sanitization layer

**Security Best Practices:**

1. **Principle of Least Privilege:**
   - Service account should have minimum required permissions in Camunda
   - Users should only access their own process instances
   - Implement role-based access control (RBAC) if needed

2. **Defense in Depth:**
   - Multiple layers of security (network, application, data)
   - Validate at each layer (frontend, backend, Camunda)
   - Implement fail-secure defaults

3. **Secure by Default:**
   - Fail securely (deny access on error)
   - Use secure defaults for all configurations
   - Enable security features by default

4. **Regular Security Audits:**
   - Review authentication/authorization logic regularly
   - Update dependencies for security patches
   - Conduct penetration testing
   - Monitor security logs for anomalies

5. **Secrets Management:**
   - Never commit secrets to version control
   - Use secrets management tools (Vault, AWS Secrets Manager)
   - Rotate credentials regularly
   - Use different credentials for dev/staging/production

---

## Recommendation

### Best Practice Integration Approach

**Architecture Overview:**
```
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│   Angular   │────────▶│ Spring Boot  │────────▶│  Camunda 8  │
│   Frontend  │ GraphQL │   Backend    │  REST   │   Engine    │
│             │ (DGS)   │  (Netflix DGS)│         │             │
└─────────────┘         └──────────────┘         └─────────────┘
      │                        │                        │
      │                        │                        │
      │                        │◀──────────────────────│
      │                        │  HTTP Connector/       │
      │                        │  External Task         │
      │                        │  (Update Deal Status)  │
      │                        │                        │
      ▼                        ▼                        ▼
┌─────────────┐         ┌──────────────┐         ┌─────────────┐
│  Keycloak   │         │  Keycloak    │         │  Keycloak   │
│ Realm:      │         │  Service     │         │ Realm:      │
│ LSCSAD      │         │  Account     │         │ camunda-   │
│             │         │  (Backend)   │         │ platform    │
└─────────────┘         └──────────────┘         └─────────────┘
                              │
                              │ Token Exchange (Optional)
                              │ or Service Account
                              ▼
                        ┌──────────────┐
                        │  Keycloak     │
                        │  Realm:       │
                        │  LSCSAD       │
                        │  (for Camunda │
                        │   callbacks)  │
                        └──────────────┘
```

**Implementation Steps:**

**1. Backend Configuration (Spring Boot)**

**Step 1.1: Add Dependencies**
- `spring-boot-starter-oauth2-resource-server` (for LSCSAD token validation)
- `spring-boot-starter-webflux` (for WebClient to call Camunda REST API)
- `camunda-zeebe-client-java` (optional, if using Zeebe client directly)
- `netflix-dgs-spring-boot-starter` (for GraphQL with Netflix DGS)
- `graphql-java` (GraphQL Java implementation)
- Lombok (for reducing boilerplate)

**Step 1.2: Configure Keycloak Integration**
- Configure OAuth2 Resource Server for LSCSAD realm token validation
- Configure Camunda realm client credentials (environment variables)
- Set up WebClient bean for Camunda REST API calls

**Step 1.3: Implement Core Services**
- `CamundaAuthenticationService`: Handles Camunda realm token acquisition and caching (service account or token exchange)
- `CamundaTokenExchangeService`: Optional - handles token exchange between realms
- `LscsadAuthenticationService`: Handles LSCSAD realm token acquisition for Camunda callbacks
- `UserIdentityMappingService`: Maps LSCSAD user to Camunda user identifier
- `CamundaRestClientService`: Wraps Camunda REST API calls
- `DealProcessService`: Business logic for deal process orchestration

**Step 1.4: Create GraphQL Resolvers (Netflix DGS)**
- `DealProcessDataFetcher`: GraphQL resolvers for deal process operations
- Implement mutations: `initiateDeal`, `submitDealInfo`, `submitParties`, `submitContacts`, `completeDeal`
- Implement queries: `dealProcess`, `dealProcessStatus`
- Add security context extraction from GraphQL data fetching environment
- Use `@DgsMutation` and `@DgsQuery` annotations

**Step 1.5: Create REST Controllers (Alternative/Additional)**
- `DealProcessController`: Exposes REST endpoints (if not using GraphQL exclusively)
- Implement all endpoints: `/initiate`, `/deal-info`, `/parties`, `/contacts`, `/complete`, `/status`
- Add `@PreAuthorize` annotations for authorization
- Extract user from `@AuthenticationPrincipal Jwt`

**Step 1.6: Implement Camunda Callback Handler**
- `DealStatusUpdateController`: REST endpoint for Camunda to call after process completion
- Authenticate using LSCSAD service account token
- Update deal status in application database
- Handle process completion callbacks

**Step 1.5: Implement Security Configuration**
- Configure Spring Security for JWT validation
- Set up CORS for Angular origin
- Configure exception handling for security errors

**2. Frontend Configuration (Angular)**

**Step 2.1: Configure Keycloak Integration**
- Install `keycloak-js` package
- Create `KeycloakInitService` to initialize Keycloak adapter
- Configure LSCSAD realm connection

**Step 2.2: Create HTTP Interceptor**
- Implement `AuthInterceptor` to add `Authorization` header to all requests
- Handle token refresh automatically
- Handle 401 errors (redirect to login)
- Works for both GraphQL and REST requests

**Step 2.3: Create GraphQL Client Service**
- Install `@apollo/client` or `graphql-request` package
- Configure Apollo Client or GraphQL client with authentication
- Set up GraphQL endpoint URL (`/graphql`)
- Configure error handling and retry logic

**Step 2.4: Create Deal GraphQL Service**
- Implement `DealGraphQLService` using GraphQL client
- Define GraphQL queries and mutations
- Use TypeScript code generation from GraphQL schema (if using codegen)
- Handle GraphQL errors and network errors

**Step 2.5: Create Deal REST Service (Alternative)**
- Implement `DealService` with methods for all process steps (if using REST)
- Use Angular `HttpClient` with proper error handling
- Define TypeScript interfaces for DTOs

**Step 2.4: Create Components**
- `DealInitiateComponent`: Button to start process
- `DealInfoComponent`: Deal information form
- `PartiesComponent`: Parties form
- `ContactsComponent`: Contacts form
- `CompleteComponent`: Completion confirmation
- `DealSummaryComponent`: Summary view

**Step 2.5: Configure Routes**
- Set up Angular routes for all steps
- Implement route guards (`AuthGuard`) for authentication
- Handle route parameters (process instance ID)

**Step 2.6: Implement State Management**
- Store process instance ID in service or NgRx store
- Handle browser refresh (reload from backend)
- Implement form auto-save (local storage)

**3. Camunda BPMN Process Design**

**Step 3.1: Design Process Definition**
- Process Definition Key: `deal-initiation-process`
- Start Event → User Task (Deal Info) → User Task (Parties) → User Task (Contacts) → Service Task (Validate) → HTTP Connector (Update Deal Status) → End Event

**Step 3.2: Configure User Tasks**
- Set form keys: `deal-info`, `parties`, `contacts`
- Configure task assignment (use `initiatedBy` variable)
- Set task names and descriptions

**Step 3.3: Configure HTTP Connector (For Callback)**
- Add HTTP Connector before End Event
- Configure URL: `https://api.example.com/api/deals/{businessKey}/status-update`
- Method: POST
- Authentication: Use LSCSAD service account token
- Headers: `Authorization: Bearer ${lscsadToken}`
- Body: Include process variables (dealInfo, parties, contacts, status)
- Error Handling: Configure retry logic and error handling

**Step 3.4: Define Process Variables**
- `initiatedBy` (String)
- `initiatedAt` (String)
- `dealInfo` (JSON)
- `parties` (JSON)
- `contacts` (JSON)
- `businessKey` (String)
- `status` (String) - e.g., "COMPLETED", "FAILED"

**Step 3.5: Deploy Process**
- Deploy BPMN file to Camunda 8
- Verify process definition is available
- Test process start via REST API
- Test HTTP connector callback (verify backend receives callback)

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
                        │ Angular │ (Stores token in memory)
                        └─────────┘

2. Initialize Deal Process (GraphQL)
   ┌─────────┐
   │ Angular │ ──GraphQL Mutation: initiateDeal
   │         │    POST /graphql
   │         │    Headers: Authorization: Bearer <LSCSAD_TOKEN>
   │         │    Body: {
   │         │      mutation {
   │         │        initiateDeal {
   │         │          processInstanceId
   │         │          currentStep
   │         │          taskId
   │         │        }
   │         │      }
   │         │    }
   └─────────┘    │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │
            │   Backend    │
            │ (Netflix DGS) │
            └──────────────┘
                  │
                  │ 1. Validate LSCSAD token (Spring Security)
                  │ 2. Extract user identity from GraphQL context
                  │ 3. Map to Camunda user
                  │
                  ▼
            ┌──────────────┐
            │   Keycloak   │ ──Client Credentials Grant──▶
            │   Service    │    POST /realms/camunda-platform/...
            │   Account    │    Body: grant_type=client_credentials
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
                  │ Body: {
                  │   bpmnProcessId: "deal-initiation-process",
                  │   variables: {
                  │     initiatedBy: "user@example.com",
                  │     initiatedAt: "2024-01-15T10:00:00Z"
                  │   },
                  │   businessKey: "DEAL-2024-001"
                  │ }
                  ▼
            ┌──────────────┐
            │   Camunda 8  │
            │   REST API   │
            └──────────────┘
                  │
                  │ Returns ProcessInstanceDto
                  │ {
                  │   processInstanceKey: "123456",
                  │   bpmnProcessId: "deal-initiation-process",
                  │   state: "ACTIVE"
                  │ }
                  ▼
            ┌──────────────┐
            │ Spring Boot  │ ──GET /v1/tasks?processInstanceKey=123456──▶
            │   Backend    │
            └──────────────┘
                  │
                  │ Returns TaskDto[]
                  │ [{
                  │   key: 789,
                  │   name: "Enter Deal Info",
                  │   formKey: "deal-info"
                  │ }]
                  ▼
            ┌──────────────┐
            │ Spring Boot  │ ──Returns GraphQL Response──▶
            │   Backend    │    {
            └──────────────┘      data: {
                                    initiateDeal: {
                                      processInstanceId: "123456",
                                      currentStep: "deal-info",
                                      taskId: "789"
                                    }
                                  }
                                }
                  │
                  ▼
            ┌─────────┐
            │ Angular │ ──Navigate to /deals/123456/deal-info
            └─────────┘

3. Submit Deal Info Form (GraphQL)
   ┌─────────┐
   │ Angular │ ──GraphQL Mutation: submitDealInfo
   │         │    POST /graphql
   │         │    Headers: Authorization: Bearer <LSCSAD_TOKEN>
   │         │    Body: {
   │         │      mutation {
   │         │        submitDealInfo(
   │         │          processInstanceId: "123456",
   │         │          dealInfo: {
   │         │            dealName: "Acme Corp Deal",
   │         │            dealType: "M&A",
   │         │            description: "...",
   │         │            value: 1000000
   │         │          }
   │         │        ) {
   │         │          processInstanceId
   │         │          currentStep
   │         │          taskId
   │         │        }
   │         │      }
   │         │    }
   │         │    Headers: Authorization: Bearer <LSCSAD_TOKEN>
   │         │    Body: {
   │         │      dealName: "Acme Corp Deal",
   │         │      dealType: "M&A",
   │         │      description: "...",
   │         │      value: 1000000
   │         │    }
   └─────────┘    │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │
            │   Backend    │
            └──────────────┘
                  │
                  │ 1. Validate LSCSAD token
                  │ 2. Get process instance (validate ownership)
                  │ 3. Get active task
                  │ 4. Update process variables
                  │    PATCH /v1/process-instances/123456/variables
                  │    Body: {
                  │      variables: {
                  │        dealInfo: {
                  │          value: "{...}",
                  │          type: "Json"
                  │        }
                  │      }
                  │    }
                  │ 5. Complete task
                  │    POST /v1/tasks/789/complete
                  │    Body: {
                  │      variables: {...}
                  │    }
                  │ 6. Get next active task
                  │    GET /v1/tasks?processInstanceKey=123456
                  │
                  ▼
            ┌──────────────┐
            │   Camunda 8  │ ──Process advances to "Add Parties" task──▶
            │   REST API   │
            └──────────────┘
                  │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │ ──Returns GraphQL Response──▶
            │   Backend    │    {
            └──────────────┘      data: {
                                    submitDealInfo: {
                                      processInstanceId: "123456",
                                      currentStep: "parties",
                                      taskId: "790"
                                    }
                                  }
                                }
                  │
                  ▼
            ┌─────────┐
            │ Angular │ ──Navigate to /deals/123456/parties
            └─────────┘

4. Submit Parties Form (GraphQL - Similar to Step 3)
   ┌─────────┐
   │ Angular │ ──GraphQL Mutation: submitParties
   │         │    POST /graphql
   │         │    mutation {
   │         │      submitParties(
   │         │        processInstanceId: "123456",
   │         │        parties: [...]
   │         │      ) { ... }
   │         │    }
   │         │    Body: {
   │         │      parties: [
   │         │        { name: "Acme Corp", type: "Company", role: "Buyer" },
   │         │        { name: "Beta Inc", type: "Company", role: "Seller" }
   │         │      ]
   │         │    }
   └─────────┘    │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │ ──Update variables, complete task──▶
            │   Backend    │
            └──────────────┘
                  │
                  ▼
            ┌──────────────┐
            │   Camunda 8  │ ──Process advances to "Add Contacts" task──▶
            │   REST API   │
            └──────────────┘
                  │
                  ▼
            ┌─────────┐
            │ Angular │ ──Navigate to /deals/123456/contacts
            └─────────┘

5. Submit Contacts Form (GraphQL - Similar to Step 3)
   ┌─────────┐
   │ Angular │ ──GraphQL Mutation: submitContacts
   │         │    POST /graphql
   │         │    mutation {
   │         │      submitContacts(
   │         │        processInstanceId: "123456",
   │         │        contacts: [...]
   │         │      ) { ... }
   │         │    }
   │         │    Body: {
   │         │      contacts: [
   │         │        { firstName: "John", lastName: "Doe", email: "john@acme.com" },
   │         │        { firstName: "Jane", lastName: "Smith", email: "jane@beta.com" }
   │         │      ]
   │         │    }
   └─────────┘    │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │ ──Update variables, complete task──▶
            │   Backend    │
            └──────────────┘
                  │
                  ▼
            ┌──────────────┐
            │   Camunda 8  │ ──Process advances to "Complete" step──▶
            │   REST API   │
            └──────────────┘
                  │
                  ▼
            ┌─────────┐
            │ Angular │ ──Navigate to /deals/123456/complete
            └─────────┘

6. Complete Deal Process (GraphQL)
   ┌─────────┐
   │ Angular │ ──GraphQL Mutation: completeDeal
   │         │    POST /graphql
   │         │    mutation {
   │         │      completeDeal(processInstanceId: "123456") {
   │         │        success
   │         │        processInstanceId
   │         │        summary {
   │         │          dealName
   │         │          partiesCount
   │         │          contactsCount
   │         │        }
   │         │      }
   │         │    }
   └─────────┘    │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │
            │   Backend    │
            └──────────────┘
                  │
                  │ 1. Validate all required data present
                  │ 2. Complete final task
                  │    POST /v1/tasks/791/complete
                  │ 3. Process instance completes (reaches end event)
                  │
                  ▼
            ┌──────────────┐
            │   Camunda 8  │ ──Process instance state: COMPLETED──▶
            │   REST API   │
            └──────────────┘
                  │
                  │ Process reaches end event
                  │ HTTP Connector or External Task triggered
                  │
                  ▼
            ┌──────────────┐
            │   Camunda 8  │ ──HTTP Connector/External Task──▶
            │   Engine     │    Calls backend to update deal status
            └──────────────┘
                  │
                  │ POST /api/deals/123456/status-update
                  │ Headers: Authorization: Bearer <LSCSAD_SERVICE_TOKEN>
                  │ Body: {
                  │   processInstanceId: "123456",
                  │   status: "COMPLETED",
                  │   completedAt: "2024-01-15T10:30:00Z"
                  │ }
                  ▼
            ┌──────────────┐
            │ Spring Boot  │
            │   Backend    │
            │ (Callback)   │
            └──────────────┘
                  │
                  │ 1. Validate LSCSAD service account token
                  │ 2. Update deal status in database
                  │ 3. Send notifications (optional)
                  │ 4. Return success response
                  │
                  ▼
            ┌──────────────┐
            │ Spring Boot  │ ──Returns GraphQL Response──▶
            │   Backend    │    {
            └──────────────┘      data: {
                                    completeDeal: {
                                      success: true,
                                      processInstanceId: "123456",
                                      summary: {
                                        dealName: "Acme Corp Deal",
                                        partiesCount: 2,
                                        contactsCount: 2
                                      }
                                    }
                                  }
                                }
                  │
                  ▼
            ┌─────────┐
            │ Angular │ ──Show success message, redirect to dashboard
            └─────────┘

7. Reverse Flow: Camunda Calls Backend (After Process Completion)
   ┌──────────────┐
   │   Camunda 8  │ ──HTTP Connector configured in BPMN──▶
   │   Engine     │    POST /api/deals/{businessKey}/status-update
   └──────────────┘    │
                       │ Headers: Authorization: Bearer <LSCSAD_SERVICE_TOKEN>
                       │ Body: {
                       │   processInstanceId: "123456",
                       │   status: "COMPLETED",
                       │   dealInfo: {...},
                       │   parties: [...],
                       │   contacts: [...]
                       │ }
                       ▼
                 ┌──────────────┐
                 │ Spring Boot  │
                 │   Backend    │
                 │ (Callback    │
                 │  Endpoint)    │
                 └──────────────┘
                       │
                       │ 1. Authenticate using LSCSAD service account
                       │    (or token exchange from camunda-platform token)
                       │ 2. Validate request (verify process instance exists)
                       │ 3. Update deal status in application database
                       │ 4. Trigger business logic (notifications, integrations)
                       │ 5. Return success response
                       │
                       ▼
                 ┌──────────────┐
                 │   Database   │ ──Deal status updated──▶
                 │              │    status: "COMPLETED"
                 └──────────────┘
```

---

### Key Implementation Considerations

**1. User Identity Mapping:**
- **Strategy:** Use email address as primary mapping identifier (most reliable if same email exists in both realms)
- **Fallback:** Use `preferred_username` if email not available
- **Implementation:** Create `UserIdentityMappingService` that extracts identity from JWT claims with fallback logic
- **Edge Cases:** Handle users that don't exist in Camunda realm:
  - Option 1: Create user in Camunda realm (if user sync enabled)
  - Option 2: Use service account identity with user identifier stored in process variables
  - Option 3: Reject request with user-friendly error message
- **Security:** Validate mapping consistency, prevent identity spoofing, log all mapping operations

**2. Token Management:**
- **Camunda Realm Token:** Store in backend only (in-memory cache), never expose to frontend
- **Caching:** Cache Camunda realm access tokens with TTL (typically 5 minutes, refresh 1 minute before expiry)
- **Refresh:** Implement automatic token refresh before expiration, handle refresh failures gracefully
- **Storage:** Store client credentials securely (environment variables, Kubernetes secrets, HashiCorp Vault)
- **Rotation:** Implement credential rotation strategy (update credentials without downtime)
- **Monitoring:** Log token refresh operations, monitor token expiration patterns

**3. Process State Management:**
- **Tracking:** Use process instance key (numeric ID) as primary identifier
- **Validation:** Always validate process instance ownership before operations (check `initiatedBy` variable)
- **State Queries:** Query active tasks to determine current step (`GET /v1/tasks?processInstanceKey={key}`)
- **Error Handling:** Handle process instance not found, already completed, or invalid state gracefully
- **Recovery:** Provide mechanism to recover from invalid states (restart process, manual intervention, admin override)
- **Concurrency:** Handle concurrent modifications (optimistic locking, version checks)

**4. Error Handling:**
- **Frontend:** Display user-friendly error messages (toast notifications, inline errors)
- **Backend:** Log detailed errors for debugging, return generic messages to frontend
- **Camunda Errors:** Map Camunda-specific errors to user-friendly messages (e.g., "Process not found" → "This deal process is no longer available")
- **Network Errors:** Implement retry logic with exponential backoff (3 retries, 1s, 2s, 4s delays)
- **Validation Errors:** Return field-level validation errors for form display (e.g., `{ field: "dealName", message: "Deal name is required" }`)
- **Error Codes:** Use consistent error codes across all endpoints for frontend handling

**5. Security:**
- **Authentication:** Validate all tokens (signature, expiration, issuer) using Spring Security
- **Authorization:** Enforce process instance ownership checks on every operation
- **Input Validation:** Validate and sanitize all input using DTOs with validation annotations
- **Rate Limiting:** Implement rate limiting on all endpoints (e.g., 100 requests/minute per user)
- **CORS:** Configure CORS to allow only trusted origins (Angular app URL)
- **Audit Logging:** Log all process operations with user identity, process instance ID, timestamp
- **Secrets:** Never commit secrets to version control, use secrets management tools

**6. Performance:**
- **Token Caching:** Cache Camunda realm tokens to reduce Keycloak load (5-minute TTL)
- **Request Batching:** Batch multiple Camunda API calls when possible (if supported)
- **Connection Pooling:** Use connection pooling for HTTP clients (WebClient default)
- **Async Operations:** Use async/await or reactive programming for non-blocking operations
- **Database Queries:** Optimize process instance queries (index on `initiatedBy` if stored in database)

**7. Monitoring and Observability:**
- **Logging:** Log all process operations, API calls, and errors with structured logging (JSON format)
- **Metrics:** Track process instance creation rate, completion rate, average time per step, error rates
- **Tracing:** Implement distributed tracing for request flow (Spring Cloud Sleuth, Zipkin)
- **Alerts:** Set up alerts for high error rates, failed authentications, process failures
- **Dashboards:** Create dashboards for process metrics, user activity, system health

**8. Testing:**
- **Unit Tests:** Test authentication, mapping, and service logic (mock Camunda API)
- **Integration Tests:** Test end-to-end flow with test Keycloak and Camunda instances
- **Security Tests:** Test authentication, authorization, and input validation
- **Load Tests:** Test performance under load (100 concurrent users, 1000 requests/minute)
- **E2E Tests:** Test complete user flow from Angular to Camunda

**9. Deployment:**
- **Environment Variables:** Use environment variables for all configuration (URLs, credentials)
- **Docker:** Containerize backend and frontend applications
- **Kubernetes:** Deploy with proper secrets management, health checks, resource limits
- **CI/CD:** Automate testing and deployment pipelines
- **Rollback:** Implement rollback strategy for failed deployments

**10. GraphQL Integration:**
- **GraphQL Schema:** Define GraphQL schema for deal process operations (mutations and queries)
- **Netflix DGS:** Use Netflix DGS framework for GraphQL resolvers
- **Type Safety:** Use GraphQL code generation for TypeScript types (if using codegen)
- **Error Handling:** Handle GraphQL errors properly (network errors, validation errors, business logic errors)
- **Security:** Extract user identity from GraphQL data fetching environment (DgsDataFetchingEnvironment)
- **Camunda REST:** Backend translates GraphQL operations to Camunda REST API calls
- **Note:** Camunda 8.8 does not support GraphQL natively - backend acts as GraphQL-to-REST adapter

**11. Reverse Authentication (Camunda → Backend):**
- **Service Account:** Create service account in LSCSAD realm for Camunda callbacks
- **Token Acquisition:** Camunda obtains LSCSAD token using client credentials grant
- **HTTP Connector:** Configure Camunda HTTP connector with authentication headers
- **External Task Worker:** Alternative pattern - use external task worker in backend to poll Camunda
- **Token Exchange:** Optional - use token exchange if user context is required in callbacks
- **Callback Endpoint:** Create secure endpoint in backend for Camunda callbacks
- **Validation:** Validate callback requests (verify process instance, check signatures if needed)
- **Idempotency:** Handle duplicate callbacks gracefully (idempotent operations)

**12. Documentation:**
- **API Documentation:** Document GraphQL schema and REST endpoints with OpenAPI/Swagger
- **Process Documentation:** Document BPMN process flow, variables, tasks, HTTP connectors
- **User Guide:** Create user guide for deal initiation process
- **Developer Guide:** Document integration architecture, setup instructions, troubleshooting
- **GraphQL Schema:** Document GraphQL schema with descriptions and examples
- **Callback Documentation:** Document callback endpoints and authentication requirements

---

## Conclusion

This integration approach provides a secure, maintainable, and scalable solution for connecting Angular front-end, Spring Boot backend, and Camunda 8.8 with cross-realm Keycloak authentication. The backend proxy pattern ensures security by never exposing Camunda realm credentials to the frontend, while the service account pattern provides reliable authentication to Camunda APIs. User identity mapping enables seamless user experience while maintaining proper access control.

The recommended architecture follows industry best practices for microservices security, API design, and process orchestration, ensuring a production-ready implementation that can scale and evolve with business requirements.

**Next Steps:**
1. Review the example implementation files in `camunda-integration-examples/`
2. Set up Keycloak realms and clients (LSCSAD and camunda-platform)
3. Configure Spring Boot backend with provided examples
4. Implement Angular frontend components and services
5. Deploy and test end-to-end flow
6. Monitor and optimize based on metrics
