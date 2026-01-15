FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="BMO RDP Team"
LABEL description="Server 2 - Regulatory, Ingestion (forex)"

# Install supervisor and utilities
RUN apk add --no-cache supervisor bash curl

# Create app directory
WORKDIR /app

# Create log directory
RUN mkdir -p /var/log

# Copy service JARs (built from parent project)
COPY regulatory-service/target/regulatory-service-*.jar /app/regulatory-service.jar
COPY ingestion-service/target/ingestion-service-*.jar /app/ingestion-service.jar

# Copy supervisor config
COPY docker/server2/supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Expose ports
# 8090 - Regulatory Service
# 8083 - Ingestion Service (forex)
EXPOSE 8090 8083

# Health check on Regulatory Service
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8090/actuator/health || exit 1

# Start supervisor
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
