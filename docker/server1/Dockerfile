FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="BMO RDP Team"
LABEL description="Server 1 - Eureka, Trade Receiver (equity), Reference Lookup, Check Eligible"

# Install supervisor and utilities
RUN apk add --no-cache supervisor bash curl

# Create app directory
WORKDIR /app

# Create log directory
RUN mkdir -p /var/log

# Copy service JARs (built from parent project)
COPY eureka-server/target/eureka-server-*.jar /app/eureka-server.jar
COPY trade-receiver-service/target/trade-receiver-service-*.jar /app/trade-receiver-service.jar
COPY reference-lookup-service/target/reference-lookup-service-*.jar /app/reference-lookup-service.jar
COPY check-eligible-service/target/check-eligible-service-*.jar /app/check-eligible-service.jar

# Copy supervisor config
COPY docker/server1/supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Expose ports
# 8088 - Eureka Server
# 8082 - Trade Receiver Service (equity)
# 8081 - Reference Lookup Service
# 8092 - Check Eligible Service
EXPOSE 8088 8082 8081 8092

# Health check on Eureka
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD curl -f http://localhost:8088/actuator/health || exit 1

# Start supervisor
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
