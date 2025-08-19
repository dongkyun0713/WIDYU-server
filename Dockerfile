# Build stage
FROM amazoncorretto:21-alpine AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew clean bootJar --no-daemon

# Run stage
FROM amazoncorretto:21-alpine
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
