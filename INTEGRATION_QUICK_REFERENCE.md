# Camunda 8.8 Integration Quick Reference

## Architecture Decision Summary

**✅ Recommended Approach: Backend Proxy Pattern with Service Account Authentication**

- **Frontend → Backend**: Uses LSCSAD realm tokens (user authentication)
- **Backend → Camunda**: Uses camunda-platform realm service account (client credentials)
- **User Mapping**: Email/username mapping between realms
- **All Camunda API calls**: Routed through Spring Boot backend

## Key Components

### 1. Authentication Flow
```
User → Angular (LSCSAD token) → Spring Boot (validates) → Camunda (service account token)
```

### 2. Process Flow
```
Initialize → Deal Info → Parties → Contacts → Complete
```

### 3. API Endpoints
```
POST   /api/deals/initiate
POST   /api/deals/{id}/deal-info
POST   /api/deals/{id}/parties
POST   /api/deals/{id}/contacts
POST   /api/deals/{id}/complete
GET    /api/deals/{id}/status
```

## Security Checklist

- [ ] Camunda realm credentials stored securely (env vars/secrets)
- [ ] Backend validates all LSCSAD tokens
- [ ] Process instance ownership validated before operations
- [ ] HTTPS/TLS enabled for all communications
- [ ] Rate limiting implemented on backend endpoints
- [ ] Input validation on all endpoints
- [ ] CORS configured for trusted origins only
- [ ] Audit logging enabled for process operations

## Configuration Checklist

### Backend (Spring Boot)
- [ ] Keycloak LSCSAD realm configured for token validation
- [ ] Camunda realm client credentials configured
- [ ] WebClient bean configured
- [ ] Security configuration with JWT resource server
- [ ] Process definition key configured

### Frontend (Angular)
- [ ] Keycloak adapter initialized
- [ ] HTTP interceptor adds Authorization header
- [ ] Deal service implemented
- [ ] Routes configured for all steps
- [ ] Error handling implemented

### Camunda
- [ ] Process definition deployed
- [ ] User tasks configured with form keys
- [ ] Process variables defined
- [ ] Keycloak integration configured

## Common Issues & Solutions

### Issue: Token Expired
**Solution**: Backend automatically refreshes Camunda tokens. Check token caching logic.

### Issue: User Not Found in Camunda
**Solution**: Ensure user mapping strategy matches user data in both realms. Consider creating users in Camunda realm.

### Issue: Process Instance Not Found
**Solution**: Validate process instance ID and ownership. Check if process was completed or deleted.

### Issue: CORS Errors
**Solution**: Configure CORS in Spring Boot to allow Angular origin. Check preflight requests.

### Issue: 401 Unauthorized
**Solution**: Verify token is included in request. Check token expiration. Validate Keycloak configuration.

## Testing Checklist

- [ ] User can authenticate and receive LSCSAD token
- [ ] Backend can authenticate with Camunda realm
- [ ] Process instance can be started
- [ ] Forms can be submitted at each step
- [ ] Process advances correctly between steps
- [ ] Process can be completed successfully
- [ ] Error handling works for invalid states
- [ ] User can only access their own process instances

## Monitoring Points

- Process instance creation rate
- Task completion rate
- Average time per step
- Error rates by endpoint
- Token refresh frequency
- Process instance completion rate

## Documentation Files

1. **CAMUNDA_8_INTEGRATION_GUIDE.md** - Comprehensive integration guide with reasoning
2. **camunda-integration-examples/** - Example implementation files
3. **INTEGRATION_QUICK_REFERENCE.md** - This quick reference

## Next Steps

1. Review the comprehensive guide
2. Set up Keycloak realms and clients
3. Configure Spring Boot backend
4. Implement Angular frontend components
5. Deploy and test end-to-end flow
6. Monitor and optimize
