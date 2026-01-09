# Option 4: Direct Frontend Access (Single Realm - LSCSAD)

## Overview

**Architecture:**
- Frontend authenticates only to `LSCSAD` realm
- Frontend calls Camunda REST API directly using LSCSAD token
- Camunda is configured to accept LSCSAD realm tokens
- Reusable Camunda REST client service (simpler than Option 3)
- Backend handles callbacks using LSCSAD token

**Flow:**
1. Frontend authenticates with LSCSAD realm (single authentication)
2. Frontend uses LSCSAD token to call Camunda REST API directly
3. Frontend uses same LSCSAD token for backend API calls
4. Camunda uses LSCSAD token to call backend callbacks
5. Backend validates LSCSAD token for callbacks

**Pros:**
- ✅ Simplest authentication (single realm)
- ✅ Direct access to Camunda APIs
- ✅ Reusable service pattern
- ✅ Lower latency
- ✅ Unified authentication across all services
- ✅ Easier token management (one token)

**Cons:**
- ❌ Requires Camunda to accept LSCSAD realm tokens
- ❌ Requires CORS configuration on Camunda
- ❌ Less centralized control
- ❌ Camunda must be configured for LSCSAD realm

## Implementation

### 1. Single Realm Authentication Service (Frontend)

**File:** `service/option4/LscsadAuthService.ts`

```typescript
import Keycloak from 'keycloak-js';

export interface KeycloakConfig {
  url: string;
  realm: string;
  clientId: string;
}

/**
 * Service for managing authentication to LSCSAD realm only.
 * Simpler than Option 3 - single realm authentication.
 * Reusable across multiple frontend applications.
 */
export class LscsadAuthService {
  private keycloak: Keycloak.KeycloakInstance;
  private config: KeycloakConfig;
  
  constructor(config: KeycloakConfig) {
    this.config = config;
    
    this.keycloak = new Keycloak({
      url: config.url,
      realm: config.realm,
      clientId: config.clientId
    });
  }
  
  /**
   * Initialize Keycloak instance.
   */
  async init(): Promise<boolean> {
    try {
      return await this.keycloak.init({
        onLoad: 'check-sso',
        checkLoginIframe: false
      });
    } catch (error) {
      console.error('Failed to initialize Keycloak:', error);
      return false;
    }
  }
  
  /**
   * Login to LSCSAD realm.
   */
  async login(): Promise<boolean> {
    try {
      return await this.keycloak.login();
    } catch (error) {
      console.error('Failed to login:', error);
      return false;
    }
  }
  
  /**
   * Get LSCSAD token (used for both backend and Camunda API calls).
   */
  getToken(): string | undefined {
    return this.keycloak.token;
  }
  
  /**
   * Refresh token.
   */
  async refreshToken(): Promise<boolean> {
    try {
      return await this.keycloak.updateToken(30);
    } catch (error) {
      console.error('Failed to refresh token:', error);
      return false;
    }
  }
  
  /**
   * Logout.
   */
  async logout(): Promise<void> {
    await this.keycloak.logout();
  }
  
  /**
   * Check if user is authenticated.
   */
  isAuthenticated(): boolean {
    return this.keycloak.authenticated === true;
  }
  
  /**
   * Setup token refresh interval.
   */
  setupTokenRefresh(intervalSeconds: number = 60): void {
    setInterval(async () => {
      if (this.isAuthenticated()) {
        await this.refreshToken();
      }
    }, intervalSeconds * 1000);
  }
  
  /**
   * Get user ID from token.
   */
  getUserId(): string {
    if (!this.keycloak.token) {
      throw new Error('Token not available');
    }
    
    try {
      const payload = JSON.parse(atob(this.keycloak.token.split('.')[1]));
      return payload.email || payload.preferred_username || payload.sub;
    } catch (error) {
      throw new Error('Failed to extract user ID from token');
    }
  }
}
```

### 2. Reusable Camunda REST Client Service (Single Realm)

**File:** `service/option4/CamundaRestClient.ts`

This is the same reusable Camunda REST client as Option 3, but configured to use LSCSAD token instead of camunda-platform token.

```typescript
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { Injectable } from '@angular/core';

// Same interfaces as Option 3
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

/**
 * Reusable Camunda 8.8 REST API client service for single realm (LSCSAD).
 * Can be used across multiple frontend applications.
 * 
 * Usage:
 * ```typescript
 * const client = CamundaRestClient.create(
 *   http,
 *   'http://localhost:8081',
 *   () => authService.getToken()
 * );
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
  
  static create(
    http: HttpClient,
    baseUrl: string,
    getToken: () => string | undefined
  ): CamundaRestClient {
    return new CamundaRestClient(http, baseUrl, getToken);
  }
  
  private getHeaders(): HttpHeaders {
    const token = this.getToken();
    if (!token) {
      throw new Error('LSCSAD token not available');
    }
    
    return new HttpHeaders({
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    });
  }
  
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
  
  getProcessInstance(processInstanceKey: string): Observable<ProcessInstance> {
    return this.http.get<ProcessInstance>(
      `${this.baseUrl}/v1/process-instances/${processInstanceKey}`,
      { headers: this.getHeaders() }
    ).pipe(
      catchError(this.handleError)
    );
  }
  
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
  
  completeTask(taskKey: string, request?: { variables?: Record<string, ProcessVariable> }): Observable<void> {
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
  
  updateProcessVariables(
    processInstanceKey: string,
    variables: Record<string, ProcessVariable>
  ): Observable<void> {
    const body = { variables };
    
    return this.http.patch<void>(
      `${this.baseUrl}/v1/process-instances/${processInstanceKey}/variables`,
      body,
      { headers: this.getHeaders() }
    ).pipe(
      catchError(this.handleError)
    );
  }
  
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
  
  deleteProcessInstance(processInstanceKey: string): Observable<void> {
    return this.http.delete<void>(
      `${this.baseUrl}/v1/process-instances/${processInstanceKey}`,
      { headers: this.getHeaders() }
    ).pipe(
      catchError(this.handleError)
    );
  }
  
  private handleError(error: any): Observable<never> {
    let errorMessage = 'An error occurred';
    
    if (error.error instanceof ErrorEvent) {
      errorMessage = `Error: ${error.error.message}`;
    } else {
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

### 3. Deal Process Service (Frontend)

**File:** `service/option4/DealProcessService.ts`

```typescript
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';
import { CamundaRestClient, ProcessInstanceRequest, ProcessVariable } from './CamundaRestClient';
import { LscsadAuthService } from './LscsadAuthService';

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
 * Uses single realm (LSCSAD) authentication.
 */
@Injectable({
  providedIn: 'root'
})
export class DealProcessService {
  
  private camundaClient: CamundaRestClient;
  private static readonly PROCESS_DEFINITION_KEY = 'deal-initiation-process';
  
  constructor(
    private http: any,
    private authService: LscsadAuthService,
    private camundaBaseUrl: string
  ) {
    this.camundaClient = CamundaRestClient.create(
      http,
      camundaBaseUrl,
      () => authService.getToken()
    );
  }
  
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
  
  private createDeal(input: DealInput): Observable<DealResponse> {
    const businessKey = this.generateBusinessKey();
    const userId = this.authService.getUserId();
    
    const variables: Record<string, ProcessVariable> = {
      initiatedBy: {
        value: userId,
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
      variables.description = { value: input.description, type: 'String' };
    }
    
    if (input.value !== undefined) {
      variables.value = { value: input.value, type: 'Double' };
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
          map(tasks => ({ processInstance, tasks }))
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
  
  private updateDeal(processInstanceId: string, input: DealInput): Observable<DealResponse> {
    return this.camundaClient.getActiveTasks(processInstanceId).pipe(
      switchMap(tasks => {
        if (tasks.length === 0) {
          throw new Error('No active task found for process instance: ' + processInstanceId);
        }
        
        const currentTask = tasks[0];
        
        const variables: Record<string, ProcessVariable> = {
          dealName: { value: input.dealName, type: 'String' },
          dealType: { value: input.dealType, type: 'String' }
        };
        
        if (input.description) {
          variables.description = { value: input.description, type: 'String' };
        }
        
        if (input.value !== undefined) {
          variables.value = { value: input.value, type: 'Double' };
        }
        
        if (input.parties) {
          variables.parties = { value: JSON.stringify(input.parties), type: 'Json' };
        }
        
        if (input.contacts) {
          variables.contacts = { value: JSON.stringify(input.contacts), type: 'Json' };
        }
        
        return this.camundaClient.updateProcessVariables(processInstanceId, variables).pipe(
          switchMap(() => this.camundaClient.completeTask(currentTask.key, { variables })),
          switchMap(() => this.camundaClient.getActiveTasks(processInstanceId)),
          switchMap(nextTasks => 
            this.camundaClient.getProcessVariables(processInstanceId).pipe(
              map(processVars => ({ nextTasks, processVars }))
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
  
  getDeal(processInstanceId: string): Observable<DealResponse> {
    return this.camundaClient.getProcessInstance(processInstanceId).pipe(
      switchMap(processInstance => 
        this.camundaClient.getProcessVariables(processInstanceId).pipe(
          map(variables => ({ processInstance, variables }))
        )
      ),
      switchMap(({ processInstance, variables }) => 
        this.camundaClient.getActiveTasks(processInstanceId).pipe(
          map(tasks => ({ processInstance, variables, tasks }))
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
  
  private getStepFromTask(task: any): string {
    return task.formKey || task.name.toLowerCase().replace(/\s+/g, '-');
  }
}
```

### 4. Angular Module Configuration

**File:** `service/option4/camunda-client.module.ts`

```typescript
import { NgModule } from '@angular/core';
import { HttpClientModule } from '@angular/common/http';
import { CamundaRestClient } from './CamundaRestClient';
import { LscsadAuthService } from './LscsadAuthService';
import { DealProcessService } from './DealProcessService';

@NgModule({
  imports: [HttpClientModule],
  providers: [
    {
      provide: LscsadAuthService,
      useFactory: () => {
        return new LscsadAuthService({
          url: 'http://keycloak:8080',
          realm: 'LSCSAD',
          clientId: 'angular-app'
        });
      }
    },
    {
      provide: DealProcessService,
      useFactory: (http: HttpClient, authService: LscsadAuthService) => {
        return new DealProcessService(
          http,
          authService,
          'http://localhost:8081' // Camunda REST API base URL
        );
      },
      deps: [HttpClient, LscsadAuthService]
    }
  ]
})
export class CamundaClientModule {
  static forRoot(config: {
    keycloakUrl: string;
    realm: string;
    clientId: string;
    camundaBaseUrl: string;
  }) {
    return {
      ngModule: CamundaClientModule,
      providers: [
        {
          provide: LscsadAuthService,
          useFactory: () => {
            return new LscsadAuthService({
              url: config.keycloakUrl,
              realm: config.realm,
              clientId: config.clientId
            });
          }
        }
      ]
    };
  }
}
```

### 5. Component Usage Example

**File:** `service/option4/deal.component.ts.example`

```typescript
import { Component, OnInit } from '@angular/core';
import { LscsadAuthService } from './LscsadAuthService';
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
    private authService: LscsadAuthService,
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

## Configuration

### Frontend Environment

**File:** `environment.ts`

```typescript
export const environment = {
  keycloakUrl: 'http://keycloak:8080',
  lscsadRealm: 'LSCSAD',
  lscsadClientId: 'angular-app',
  camundaRestApiUrl: 'http://localhost:8081'
};
```

### Camunda Configuration

**Important:** Camunda must be configured to accept LSCSAD realm tokens.

```yaml
# Camunda application.yml
camunda:
  rest:
    cors:
      enabled: true
      allowed-origins:
        - http://localhost:4200
      allowed-methods:
        - GET
        - POST
        - PATCH
        - DELETE
      allowed-headers:
        - Authorization
        - Content-Type
  
  # Configure Camunda to accept LSCSAD realm tokens
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://keycloak:8080/realms/LSCSAD
          jwk-set-uri: http://keycloak:8080/realms/LSCSAD/protocol/openid-connect/certs
```

## Keycloak Setup

1. **Configure LSCSAD Realm Client:**
   - Client ID: `angular-app` (or your frontend client)
   - Client Type: `public`
   - Valid Redirect URIs: `http://localhost:4200/*`
   - Web Origins: `http://localhost:4200`
   - Standard Flow Enabled: `ON`

2. **Configure Camunda to Accept LSCSAD Tokens:**
   - Configure Camunda OAuth2 Resource Server to validate LSCSAD realm tokens
   - Set issuer URI to LSCSAD realm
   - Configure JWK set URI for LSCSAD realm

## Backend Callback Configuration

**File:** `controller/DealCallbackController.java`

Backend callback controller validates LSCSAD tokens:

```java
@RestController
@RequestMapping("/api/deals")
public class DealCallbackController {
    
    @PostMapping("/{businessKey}/status-update")
    public ResponseEntity<Map<String, String>> updateDealStatus(
            @PathVariable String businessKey,
            @RequestBody Map<String, Object> callbackData,
            @AuthenticationPrincipal Jwt jwt) {
        
        // JWT is validated against LSCSAD realm
        String userId = jwt.getClaimAsString("email");
        
        // Update deal status
        // ...
        
        return ResponseEntity.ok(Map.of("success", "true"));
    }
}
```

## Usage

Frontend uses single token for both backend and Camunda:

```typescript
// Initialize authentication (single realm)
await authService.init();
await authService.login();

// Use same token for Camunda and backend
const token = authService.getToken();

// Call Camunda REST API directly
const process = await camundaClient.startProcess({
  bpmnProcessId: 'deal-initiation-process',
  variables: { dealName: 'Acme Deal' }
});

// Call backend API with same token
const backendResponse = await http.get('/api/some-endpoint', {
  headers: { Authorization: `Bearer ${token}` }
});
```

## Advantages Over Option 3

- ✅ **Simpler:** Only one authentication flow
- ✅ **Easier token management:** Single token to manage
- ✅ **Unified authentication:** Same token for all services
- ✅ **Less complexity:** No need to manage two Keycloak instances
- ✅ **Better UX:** User only logs in once

## Requirements

- Camunda must be configured to accept LSCSAD realm tokens
- CORS must be enabled on Camunda REST API
- Frontend client must be configured in LSCSAD realm
- Backend must validate LSCSAD tokens for callbacks
