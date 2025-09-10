# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Development Commands

### Build and Run
- `./gradlew build` - Build the project
- `./gradlew bootRun` - Run the Spring Boot application
- `./gradlew test` - Run all tests
- `./gradlew test --tests "ClassName"` - Run a specific test class
- `./gradlew test --tests "ClassName.methodName"` - Run a specific test method

### Dependencies
- `./gradlew dependencies` - View all dependencies
- `./gradlew clean` - Clean build artifacts

## Architecture Overview

### Package Structure
The project follows a layered architecture organized by domain:

- `com.widyu.auth` - Authentication and authorization (main implementation)
- `com.widyu.authtest` - OAuth testing utilities for development
- `com.widyu.member` - Member/user domain logic
- `com.widyu.pay` - Payment processing
- `com.widyu.fcm` - Firebase Cloud Messaging
- `com.widyu.global` - Shared utilities, error handling, and configurations

### Layer Organization
Each domain follows this structure:
- `api/` - REST controllers and API documentation
- `api/docs/` - Swagger/OpenAPI documentation interfaces
- `application/` - Business logic services
- `domain/` - Domain entities and value objects
- `dto/` - Data transfer objects (request/response)
- `repository/` - Data access layer

### Key Technologies
- **Spring Boot 3.3.5** with Java 21
- **Spring Data JPA** with QueryDSL for database operations
- **JWT** authentication with custom token management
- **OAuth2** integration (Naver, Kakao, Apple)
- **MySQL** database with H2 for testing
- **Redis** for caching and session management
- **Firebase Admin SDK** for push notifications
- **Swagger/OpenAPI** for API documentation
- **Spring Cloud OpenFeign** for HTTP client integration
- **Coolsms SDK** for SMS messaging

### Authentication System
The authentication system supports multiple login methods:
- **Local authentication** with email/password
- **Social login** with OAuth2 providers (Naver, Kakao, Apple)
- **Dual user types**: Parents and Guardians with different flows
- **JWT-based** access/refresh token pairs

### Response Format
All API responses follow a standardized format using `ApiResponseTemplate`:
```json
{
  "code": "SUCCESS_CODE",
  "message": "Success message",
  "data": { /* response data */ }
}
```

### Configuration Management
- **Profile-based configuration**: Available profiles are `local`, `dev`, and `test`
- **Modular config files**: separate YAML files for different concerns (datasource, security, fcm, pay, redis, coolsms, etc.)
- **Environment-specific** settings with sensible defaults

### Error Handling
- Centralized error codes in `ErrorCode` enum
- HTTP status codes mapped to business error codes
- Consistent error response format across all endpoints

### Testing Structure
- `authtest` package provides OAuth testing utilities
- Separate test configurations and H2 database for testing
- Controllers implement documentation interfaces for Swagger generation

### Social Login Flow
1. **URL Generation**: Generate OAuth provider authorization URLs
2. **Callback Handling**: Process authorization codes and exchange for tokens
3. **Token Management**: Issue internal JWT tokens after successful OAuth
4. **Profile Integration**: Merge social profile data with internal user accounts

## Important Notes

### OAuth Development
- Use `authtest` package controllers for OAuth testing and token generation
- Social login responses include user profile data with provider information
- State parameters are managed via Redis for CSRF protection

### Database
- Uses QueryDSL for complex queries
- JPA entities follow domain-driven design principles
- Separate repositories for different bounded contexts

### Security
- JWT tokens have configurable expiration times
- Refresh token rotation supported
- Role-based access control with MemberRole enum