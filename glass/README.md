# Camunda 8.8 Integration Options

This directory contains implementation guides for integrating Camunda 8.8 with Spring Boot backend and Angular frontend using GraphQL (Netflix DGS) and Keycloak authentication.

## Overview

Four authentication options are available, each with different trade-offs:

- **Option 1**: Service Account Pattern (Backend → Camunda)
- **Option 2**: Direct Token Exchange (Backend → Camunda)
- **Option 3**: Direct Frontend Access with Dual Realm (Frontend → Camunda directly)
- **Option 4**: Direct Frontend Access with Single Realm (Frontend → Camunda directly, LSCSAD only)

## Quick Comparison

| Option | Frontend Auth | Backend → Camunda | Frontend → Camunda | Complexity | Security |
|--------|--------------|-------------------|-------------------|------------|----------|
| Option 1 | LSCSAD | Service Account (user1) | ❌ | Low | High |
| Option 2 | LSCSAD | Token Exchange | ❌ | Medium | High |
| Option 3 | LSCSAD + camunda-platform | ❌ | Direct (camunda-platform token) | Medium | Medium |
| Option 4 | LSCSAD only | ❌ | Direct (LSCSAD token) | Low | Medium |

## Architecture Overview

```
┌─────────────┐
│   Angular   │
│   Frontend  │
└─────────────┘
      │
      ├── Option 1/2: GraphQL → Backend → Camunda
      ├── Option 3: Direct REST (dual realm auth)
      └── Option 4: Direct REST (single realm auth)
      │
      ▼
┌─────────────┐
│ Spring Boot │
│   Backend   │
│ (Netflix DGS)│
└─────────────┘
      │
      ▼
┌─────────────┐
│  Camunda 8  │
│   Engine    │
└─────────────┘
```

## Documentation Structure

- **[README.md](README.md)** - This file (overview and quick reference)
- **[SHARED_COMPONENTS.md](SHARED_COMPONENTS.md)** - Shared components (GraphQL schema, DTOs, configs)
- **[OPTION_1_SERVICE_ACCOUNT.md](OPTION_1_SERVICE_ACCOUNT.md)** - Service Account Pattern implementation
- **[OPTION_2_TOKEN_EXCHANGE.md](OPTION_2_TOKEN_EXCHANGE.md)** - Token Exchange implementation
- **[OPTION_3_DIRECT_FRONTEND_DUAL_REALM.md](OPTION_3_DIRECT_FRONTEND_DUAL_REALM.md)** - Direct Frontend Access (dual realm)
- **[OPTION_4_DIRECT_FRONTEND_SINGLE_REALM.md](OPTION_4_DIRECT_FRONTEND_SINGLE_REALM.md)** - Direct Frontend Access (single realm)

## Choosing the Right Option

### Use Option 1 (Service Account) when:
- ✅ You need centralized control and validation
- ✅ Security is the top priority
- ✅ You want to hide Camunda implementation details from frontend
- ✅ You prefer backend-proxy pattern

### Use Option 2 (Token Exchange) when:
- ✅ You need user-level audit trails in Camunda
- ✅ You want to preserve user identity in Camunda operations
- ✅ You have Keycloak Token Exchange feature available
- ✅ You prefer backend-proxy pattern with user context

### Use Option 3 (Direct Frontend - Dual Realm) when:
- ✅ You need low latency (direct API calls)
- ✅ Multiple applications need Camunda access
- ✅ You want to build a reusable Camunda integration library
- ✅ Frontend needs real-time process updates
- ✅ You can properly secure frontend tokens
- ✅ You need separate authentication for backend and Camunda

### Use Option 4 (Direct Frontend - Single Realm) when:
- ✅ You need low latency (direct API calls)
- ✅ You want simpler authentication (single realm)
- ✅ Camunda is configured to accept LSCSAD realm tokens
- ✅ You want to reuse existing LSCSAD authentication
- ✅ You prefer unified authentication across all services

## Prerequisites

- Camunda 8.8
- Keycloak 25.0.6
- Spring Boot 3.x
- Angular 15+
- Netflix DGS Framework
- Java 17+
- Node.js 18+

## Quick Start

1. **Choose an option** based on your requirements
2. **Read the shared components** in `SHARED_COMPONENTS.md`
3. **Follow the implementation guide** for your chosen option
4. **Configure Keycloak** as described in the option guide
5. **Deploy and test**

## Keycloak Realms

- **LSCSAD**: Application realm for frontend and backend authentication
- **camunda-platform**: Camunda realm (used in Options 1, 2, and 3)

## GraphQL Schema

All options use the same GraphQL schema (see `SHARED_COMPONENTS.md`):

```graphql
type Mutation {
  deal(processInstanceId: String, input: DealInput!): Deal!
}

type Query {
  deal(processInstanceId: String!): Deal
  deals(status: DealStatus): [Deal!]!
}
```

## Common Patterns

### Process Flow
1. User initiates deal → Frontend calls GraphQL mutation (or REST API for Options 3/4)
2. Backend/Frontend starts Camunda process instance
3. User completes forms → Process advances through tasks
4. Process completes → Camunda calls backend callback
5. Backend updates deal status

### Authentication Flow
- **Options 1 & 2**: Frontend → Backend (LSCSAD) → Camunda (service account or exchanged token)
- **Option 3**: Frontend → Camunda (camunda-platform token) + Frontend → Backend (LSCSAD token)
- **Option 4**: Frontend → Camunda (LSCSAD token) + Frontend → Backend (LSCSAD token)

## Support

For questions or issues, refer to the specific option documentation or the main integration guide.
