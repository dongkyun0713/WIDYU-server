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
The project follows a domain-driven design architecture organized by bounded contexts:

- `com.widyu.domain.auth` - Authentication and authorization with OAuth2 providers
- `com.widyu.domain.member` - Member/user domain logic and profiles
- `com.widyu.domain.album` - Album/photo sharing functionality with media management
- `com.widyu.domain.pay` - Payment processing and transaction management
- `com.widyu.domain.fcm` - Firebase Cloud Messaging for push notifications
- `com.widyu.authtest` - OAuth testing utilities for development
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
The authentication system supports multiple login methods with dual user types:
- **Local authentication** with email/password for Guardians
- **Social login** with OAuth2 providers (Naver, Kakao, Apple) for Guardians
- **Invite code authentication** for Parents (code + phone number)
- **Dual user types**: Parents and Guardians with distinct authentication flows
- **JWT-based** access/refresh token pairs with automatic account linking
- **Apple iOS/Android callback** handling with intent URL generation

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
- **Modular config files**: separate YAML files for different concerns (datasource, security, fcm, pay, redis, coolsms, oauth, imp, etc.)
- **Environment-specific** settings with sensible defaults

### Error Handling
- Centralized error codes in `ErrorCode` enum
- HTTP status codes mapped to business error codes
- Consistent error response format across all endpoints

### Testing Structure
- `authtest` package provides OAuth testing utilities
- Separate test configurations and H2 database for testing
- Controllers implement documentation interfaces for Swagger generation

### Album Domain
The album domain handles photo/video sharing with sophisticated features:
- **Multi-media posts** with photos (≤10) and videos (≤3) per album
- **Feed views**: Latest posts with infinite scroll, monthly grouping, calendar view
- **Interactive features**: Likes, comments with replies, view tracking
- **Smart notifications**: Unviewed content alerts for parents
- **Media management**: File uploads, thumbnails, duration tracking for videos

### Social Login Flow
1. **URL Generation**: Generate OAuth provider authorization URLs
2. **Callback Handling**: Process authorization codes and exchange for tokens (Apple uses intent URLs for Android)
3. **Token Management**: Issue internal JWT tokens after successful OAuth
4. **Profile Integration**: Merge social profile data with internal user accounts (automatic linking by phone/name)

## Important Notes

### User Type Differences
- **Guardians**: Can use local/social login, create albums, manage family profiles
- **Parents**: Use invite codes from guardians, view-only access to albums
- **Account Linking**: Same phone number + name automatically links local and social accounts

### Album Features (Based on AlbumDocs.md)
- **Feed Tab (ALBM1)**: Latest posts with infinite scroll
- **Album Tab (ALBM2)**: Monthly grouping of posts  
- **Calendar Tab (ALBM3)**: Date-based post viewing with calendar interface
- **Notifications (NEWS1)**: Smart alerts based on parent viewing patterns
- **Content Upload**: Multi-step process with photo/video editing capabilities

### OAuth Development
- Use `authtest` package controllers for OAuth testing and token generation
- Apple authentication includes iOS/Android callback handling with intent URL generation
- State parameters are managed via Redis for CSRF protection
- Enhanced logging in AppleLoginStrategy for debugging token exchange issues

### Database Design
- Uses QueryDSL for complex queries
- Domain-driven design with bounded contexts
- **Album entities**: Album, AlbumMedia, AlbumComment, AlbumLike, AlbumView with proper relationships
- **Soft deletion** pattern with Status enum
- **Invite code sharing**: Parents can share same invite codes (duplicate codes allowed)

### Security
- JWT tokens have configurable expiration times
- Refresh token rotation supported
- Role-based access control with MemberRole enum
- Parent authentication uses invite code + phone number verification