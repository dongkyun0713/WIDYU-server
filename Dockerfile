# Multi-stage build for Spring Boot multi-module project

# Stage 1: Build stage
FROM --platform=$BUILDPLATFORM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy Gradle files for dependency caching
COPY build.gradle settings.gradle gradlew ./
COPY gradle ./gradle
COPY backend ./backend

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon || return 0

# Build the application
RUN ./gradlew :backend:widyu-api:bootJar --no-daemon -x test

# Stage 2: Runtime stage
FROM --platform=$TARGETPLATFORM amazoncorretto:21-alpine

WORKDIR /app

# Install dependencies and create non-root user
RUN apk add --no-cache \
    ffmpeg \
    wget && \
    addgroup -S spring && \
    adduser -S spring -G spring
USER spring:spring

# Copy the built JAR from builder stage
COPY --from=builder /app/backend/widyu-api/build/libs/*.jar app.jar

# Expose application port
EXPOSE 8080

# Default JVM options (overridden by JAVA_OPTS env var in compose)
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

# Health check (exec form)
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD ["wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8080/actuator/health"]

# exec guarantees java becomes PID 1 → SIGTERM handled correctly
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
