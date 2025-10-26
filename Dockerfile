# Multi-stage build for optimized image size

# Stage 1: Build stage
FROM maven:3.9-eclipse-temurin-17-alpine AS build

# Set working directory
WORKDIR /app

# Copy pom.xml first for dependency caching
COPY pom.xml .

# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jre-alpine

# Install audio libraries (required for audio processing)
RUN apk add --no-cache \
    alsa-lib \
    pulseaudio-utils \
    ffmpeg

# Create app directory
WORKDIR /app

# Copy the jar from build stage
COPY --from=build /app/target/pitchchanger-0.0.1-SNAPSHOT.jar app.jar

# Create directory for temporary audio files
RUN mkdir -p /tmp/audio && chmod 777 /tmp/audio

# Expose the default Spring Boot port
EXPOSE 8080

# Set JVM options for containerized environment
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
