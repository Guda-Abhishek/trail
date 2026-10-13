# ==============================
# 1️⃣ Build Stage (Maven + JDK)
# ==============================
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy only pom first (for caching dependencies)
COPY pom.xml .

# Download dependencies
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests


# ==============================
# 2️⃣ Runtime Stage (Alpine + JRE)
# ==============================
FROM amazoncorretto:21-alpine

WORKDIR /app

# -D: Don't assign a password
RUN adduser -D springuser

# Copy JAR from builder
COPY --from=builder /app/target/*.jar app.jar

# Change ownership to the non-root user
RUN chown springuser:springuser app.jar

USER springuser

# Expose port (matches server.port in application.properties)
EXPOSE 8080

# JVM optimizations (Alpine/musl compatible)
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
