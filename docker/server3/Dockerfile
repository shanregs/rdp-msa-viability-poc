FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="BMO RDP Team"
LABEL description="Server 3 - RefData, Ingestion (irswap), Eligibility"

# Install supervisor and utilities
RUN apk add --no-cache supervisor bash curl

# Create app directory
WORKDIR /app

# Create log directory
RUN mkdir -p /var/log

# Copy service JARs (built from parent project)
COPY refdata-service/target/refdata-service-*.jar /app/refdata-service.jar
COPY ingestion-service/target/ingestion-service-*.jar /app/ingestion-service.jar
COPY eligibility-service/target/eligibility-service-*.jar /app/eligibility-service.jar

# Copy supervisor config
COPY docker/server3/supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Expose ports
# 8081 - RefData Service
# 8082 - Ingestion Service (irswap)
# 8092 - Eligibility Service
EXPOSE 8081 8082 8092

# Health check on RefData Service
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8081/actuator/health || exit 1

# Start supervisor
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
