# Camunda 8.8 Integration Examples

This directory contains example implementation files for integrating Angular front-end, Spring Boot backend, and Camunda 8.8 with cross-realm Keycloak authentication.

## Files Overview

### Backend (Spring Boot) Examples

1. **CamundaAuthenticationService.java**
   - Handles authentication with Camunda 8.8 using Keycloak camunda-platform realm
   - Uses client credentials grant to obtain access tokens
   - Implements token caching with automatic refresh

2. **UserIdentityMappingService.java**
   - Maps user identity from LSCSAD realm JWT token to Camunda user identifier
   - Supports multiple mapping strategies (email, username, subject)
   - Provides utility methods for extracting user information from tokens

3. **CamundaRestClientService.java**
   - Service for interacting with Camunda 8.8 REST API
   - Methods for starting processes, completing tasks, updating variables
   - Handles variable conversion to Camunda format

4. **application.yml.example**
   - Example Spring Boot configuration
   - Keycloak and Camunda settings
   - Environment variable placeholders for sensitive data

### Frontend (Angular) Examples

1. **deal.service.ts**
   - Angular service for deal process operations
   - TypeScript interfaces for request/response DTOs
   - Methods for all process steps (initiate, deal-info, parties, contacts, complete)

## Usage Instructions

### Backend Setup

1. **Add Dependencies** (pom.xml or build.gradle)
   ```xml
   <dependency>
       <groupId>io.camunda</groupId>
       <artifactId>camunda-zeebe-client-java</artifactId>
       <version>8.8.0</version>
   </dependency>
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
   </dependency>
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-webflux</artifactId>
   </dependency>
   ```

2. **Configure WebClient Bean**
   ```java
   @Configuration
   public class WebClientConfig {
       @Bean
       public WebClient webClient() {
           return WebClient.builder()
               .build();
       }
   }
   ```

3. **Set Environment Variables**
   ```bash
   export CAMUNDA_CLIENT_ID=your-camunda-client-id
   export CAMUNDA_CLIENT_SECRET=your-camunda-client-secret
   ```

4. **Copy Configuration**
   - Copy `application.yml.example` to `src/main/resources/application.yml`
   - Update URLs and realm names to match your environment

5. **Implement Controller**
   - Use the examples from the main integration guide
   - Create endpoints that use these services

### Frontend Setup

1. **Install Keycloak Angular Adapter**
   ```bash
   npm install keycloak-js
   ```

2. **Configure Keycloak**
   - Create `keycloak-init.service.ts` (see main guide)
   - Configure HTTP interceptor to add Authorization header

3. **Use Deal Service**
   - Import `DealService` in your components
   - Call service methods for each process step
   - Handle responses and navigate to next step

## Key Points

- **Security**: Never expose Camunda realm credentials to frontend
- **Token Management**: Backend handles all Camunda authentication
- **User Mapping**: Map users consistently between realms
- **Error Handling**: Implement proper error handling at each layer
- **Process State**: Always validate process state before operations

## Next Steps

1. Review the main integration guide: `CAMUNDA_8_INTEGRATION_GUIDE.md`
2. Implement the controller layer using these services
3. Create Angular components for each form step
4. Configure routing and navigation
5. Test the complete flow end-to-end

## Notes

- These are example implementations - adapt to your specific requirements
- Add proper error handling and logging
- Implement unit and integration tests
- Consider adding retry logic for network operations
- Add monitoring and observability
