# Option 3: Direct Frontend Access (Dual Realm)

## Overview

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
- ✅ Direct access to Camunda APIs (no backend proxy)
- ✅ Reusable service pattern for multiple applications
- ✅ Lower latency (fewer network hops)
- ✅ Frontend has full control over Camunda operations

**Cons:**
- ❌ Frontend must manage two tokens
- ❌ Requires CORS configuration on Camunda
- ❌ Frontend must handle Camunda authentication complexity
- ❌ Less centralized control and validation

## Implementation

### 1. Camunda Authentication Service (Frontend)

**File:** `service/option3/CamundaAuthService.ts`

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
  
  getLscsadToken(): string | undefined {
    return this.lscsadKeycloak.token;
  }
  
  getCamundaToken(): string | undefined {
    return this.camundaKeycloak.token;
  }
  
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
  
  async logout(): Promise<void> {
    await Promise.all([
      this.lscsadKeycloak.logout(),
      this.camundaKeycloak.logout()
    ]);
  }
  
  isAuthenticated(): boolean {
    return this.lscsadKeycloak.authenticated === true && 
           this.camundaKeycloak.authenticated === true;
  }
  
  setupTokenRefresh(intervalSeconds: number = 60): void {
    setInterval(async () => {
      if (this.isAuthenticated()) {
        await this.refreshTokens();
      }
    }, intervalSeconds * 1000);
  }
}
```

### 2. Reusable Camunda REST Client Service

**File:** `service/option3/CamundaRestClient.ts`

See `SHARED_COMPONENTS.md` for the full reusable Camunda REST client implementation. This is a TypeScript service that can be used across multiple frontend applications.

Key methods:
- `startProcess()` - Start a new process instance
- `getProcessInstance()` - Get process instance details
- `getActiveTasks()` - Get active tasks for a process
- `completeTask()` - Complete a task
- `updateProcessVariables()` - Update process variables
- `getProcessVariables()` - Get process variables
- `deleteProcessInstance()` - Delete a process instance

### 3. Deal Process Service (Frontend)

**File:** `service/option3/DealProcessService.ts`

Frontend service that uses the reusable Camunda REST client:

```typescript
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map, switchMap } from 'rxjs/operators';
import { CamundaRestClient, ProcessInstanceRequest, ProcessVariable } from './CamundaRestClient';
import { CamundaAuthService } from './CamundaAuthService';

@Injectable({
  providedIn: 'root'
})
export class DealProcessService {
  
  private camundaClient: CamundaRestClient;
  private static readonly PROCESS_DEFINITION_KEY = 'deal-initiation-process';
  
  constructor(
    private http: any,
    private authService: CamundaAuthService,
    private camundaBaseUrl: string
  ) {
    this.camundaClient = CamundaRestClient.create(
      http,
      camundaBaseUrl,
      () => authService.getCamundaToken()
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
  
  // Implementation similar to backend service but uses frontend REST client
  // See full implementation in main guide
}
```

## Configuration

**Frontend Environment:**

```typescript
// environment.ts
export const environment = {
  keycloakUrl: 'http://keycloak:8080',
  lscsadRealm: 'LSCSAD',
  lscsadClientId: 'angular-app',
  camundaRealm: 'camunda-platform',
  camundaClientId: 'camunda-frontend-client',
  camundaRestApiUrl: 'http://localhost:8081'
};
```

## Keycloak Setup

1. **Create Frontend Client in camunda-platform realm:**
   - Client ID: `camunda-frontend-client`
   - Client Type: `public`
   - Valid Redirect URIs: `http://localhost:4200/*`
   - Web Origins: `http://localhost:4200`
   - Standard Flow Enabled: `ON`

2. **Configure CORS in Camunda:**
   - Enable CORS in Camunda REST API configuration
   - Allow frontend origin
   - Allow Authorization header

## Usage

Frontend calls Camunda REST API directly:

```typescript
// Initialize authentication
await authService.init();
await authService.login();

// Use Camunda REST client
const process = await camundaClient.startProcess({
  bpmnProcessId: 'deal-initiation-process',
  variables: { dealName: 'Acme Deal' }
});
```

## CORS Configuration

Camunda must be configured to allow CORS:

```yaml
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
```
