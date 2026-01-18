FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="BMO RDP Team"
LABEL description="Server 4 - Eureka, Trade Receiver (supporteventhandler)"

# Install supervisor and utilities
RUN apk add --no-cache supervisor bash curl

# Create app directory
WORKDIR /app

# Create log directory
RUN mkdir -p /var/log

# Copy service JARs (built from parent project)
COPY eureka-server/target/eureka-server-*.jar /app/eureka-server.jar
COPY trade-receiver-service/target/trade-receiver-service-*.jar /app/trade-receiver-service.jar

# Copy supervisor config
COPY docker/server4/supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Expose ports
# 8088 - Eureka Server
# 8084 - Trade Receiver Service (supporteventhandler)
EXPOSE 8088 8084

# Health check on Eureka
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD curl -f http://localhost:8088/actuator/health || exit 1

# Start supervisor
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
