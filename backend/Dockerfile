# ==========================================
# Multi-Stage Dockerfile for URL Shortener
# Java 25 / Spring Boot 4.1.0 Parent
# ==========================================

# Stage: Minimal & Hardened Runtime
FROM eclipse-temurin:25-jre-noble AS runner

# Create dedicated non-root application user and group
RUN groupadd -g 10001 appgroup && \
    useradd -u 10001 -g appgroup -m -s /bin/bash appuser && \
    apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy pre-built artifact with strict ownership
COPY --chown=appuser:appgroup target/url-shortener-service-*.jar ./app.jar

# Configuration environment variables with secure production defaults
ENV SERVER_PORT=8080 \
    WORKER_NODE_ID=1 \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

# Switch to unprivileged runtime user
USER appuser

EXPOSE 8080

# Production container healthcheck against Spring Boot Actuator
HEALTHCHECK --interval=20s --timeout=4s --start-period=30s --retries=3 \
  CMD sh -c 'curl -f http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1'

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
