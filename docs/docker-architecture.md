# Docker Architecture

This document describes the Docker containerization strategy for the RDP MSA Viability POC.

## Overview

Each server is represented as a single Docker container running multiple services using a supervisor process or multi-process approach.

```
┌────────────────────────────────────────────────────────────────────────────┐
│                              Docker Host                                   │
│                                                                            │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                        rdp-network (bridge)                          │  │
│  │                                                                      │  │
│  │   ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌─────────────┐    │  │
│  │   │  server1    │ │  server2    │ │  server3    │ │  server4    │    │  │
│  │   │             │ │             │ │             │ │             │    │  │
│  │   │ eureka:8088 │ │ reg:8090    │ │ ref:8081    │ │ eureka:8088 │    │  │
│  │   │ ing:8082    │ │ ing:8083    │ │ ing:8082    │ │ ing:8084    │    │  │
│  │   │ ref:8081    │ │             │ │ elig:8092   │ │             │    │  │
│  │   │ elig:8092   │ │             │ │             │ │             │    │  │
│  │   └─────────────┘ └─────────────┘ └─────────────┘ └─────────────┘    │  │
│  │                                                                      │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                            │
└────────────────────────────────────────────────────────────────────────────┘
```

## Server Configurations

### Server 1

| Service | Port | Profile |
|---------|------|---------|
| Eureka Server | 8088 | - |
| Trade Receiver Service | 8082 | equity |
| Reference Lookup Service | 8081 | - |
| Check Eligible Service | 8092 | - |

### Server 2

| Service | Port | Profile |
|---------|------|---------|
| EQ Trade Handler Service | 8090 | - |
| Trade Receiver Service | 8083 | forex |

### Server 3

| Service | Port | Profile |
|---------|------|---------|
| Reference Lookup Service | 8081 | - |
| Trade Receiver Service | 8082 | irswap |
| Check Eligible Service | 8092 | - |

### Server 4

| Service | Port | Profile |
|---------|------|---------|
| Eureka Server | 8088 | - |
| Trade Receiver Service | 8084 | supporteventhandler |

## Dockerfile Templates

### Base Dockerfile (Multi-Service)

```dockerfile
# Base image with Java 21
FROM eclipse-temurin:21-jre-alpine

# Install supervisor for multi-process management
RUN apk add --no-cache supervisor

# Create app directory
WORKDIR /app

# Copy all service JARs
COPY target/*.jar /app/

# Copy supervisor configuration
COPY supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Expose ports (varies by server)
EXPOSE 8081 8082 8088 8092

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -q --spider http://localhost:8088/actuator/health || exit 1

# Start supervisor
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
```

### Server 1 Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache supervisor bash curl

WORKDIR /app

# Copy service JARs
COPY eureka-server/target/eureka-server-*.jar /app/eureka-server.jar
COPY trade-receiver-service/target/trade-receiver-service-*.jar /app/trade-receiver-service.jar
COPY reference-lookup-service/target/reference-lookup-service-*.jar /app/reference-lookup-service.jar
COPY check-eligible-service/target/check-eligible-service-*.jar /app/check-eligible-service.jar

# Copy supervisor config
COPY docker/server1/supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Expose ports
EXPOSE 8081 8082 8088 8092

# Health check on Eureka
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD curl -f http://localhost:8088/actuator/health || exit 1

CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
```

### Server 2 Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache supervisor bash curl

WORKDIR /app

# Copy service JARs
COPY eq-trade-handler-service/target/eq-trade-handler-service-*.jar /app/eq-trade-handler-service.jar
COPY trade-receiver-service/target/trade-receiver-service-*.jar /app/trade-receiver-service.jar

# Copy supervisor config
COPY docker/server2/supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Expose ports
EXPOSE 8083 8090

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8090/actuator/health || exit 1

CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
```

### Server 3 Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache supervisor bash curl

WORKDIR /app

# Copy service JARs
COPY reference-lookup-service/target/reference-lookup-service-*.jar /app/reference-lookup-service.jar
COPY trade-receiver-service/target/trade-receiver-service-*.jar /app/trade-receiver-service.jar
COPY check-eligible-service/target/check-eligible-service-*.jar /app/check-eligible-service.jar

# Copy supervisor config
COPY docker/server3/supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Expose ports
EXPOSE 8081 8082 8092

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8081/actuator/health || exit 1

CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
```

### Server 4 Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache supervisor bash curl

WORKDIR /app

# Copy service JARs
COPY eureka-server/target/eureka-server-*.jar /app/eureka-server.jar
COPY trade-receiver-service/target/trade-receiver-service-*.jar /app/trade-receiver-service.jar

# Copy supervisor config
COPY docker/server4/supervisord.conf /etc/supervisor/conf.d/supervisord.conf

# Expose ports
EXPOSE 8084 8088

# Health check on Eureka
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD curl -f http://localhost:8088/actuator/health || exit 1

CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]
```

## Supervisor Configurations

### Server 1 supervisord.conf

```ini
[supervisord]
nodaemon=true
logfile=/var/log/supervisord.log
pidfile=/var/run/supervisord.pid

[program:eureka-server]
command=java -jar /app/eureka-server.jar --server.port=8088
autostart=true
autorestart=true
priority=1
stdout_logfile=/var/log/eureka-server.log
stderr_logfile=/var/log/eureka-server-error.log

[program:reference-lookup-service]
command=java -jar /app/reference-lookup-service.jar --server.port=8081
autostart=true
autorestart=true
priority=2
startsecs=30
stdout_logfile=/var/log/reference-lookup-service.log
stderr_logfile=/var/log/reference-lookup-service-error.log

[program:check-eligible-service]
command=java -jar /app/check-eligible-service.jar --server.port=8092
autostart=true
autorestart=true
priority=2
startsecs=30
stdout_logfile=/var/log/check-eligible-service.log
stderr_logfile=/var/log/check-eligible-service-error.log

[program:trade-receiver-service]
command=java -jar /app/trade-receiver-service.jar --server.port=8082 --spring.profiles.active=equity
autostart=true
autorestart=true
priority=3
startsecs=45
stdout_logfile=/var/log/trade-receiver-service.log
stderr_logfile=/var/log/trade-receiver-service-error.log
```

### Server 2 supervisord.conf

```ini
[supervisord]
nodaemon=true
logfile=/var/log/supervisord.log
pidfile=/var/run/supervisord.pid

[program:eq-trade-handler-service]
command=java -jar /app/eq-trade-handler-service.jar --server.port=8090
autostart=true
autorestart=true
priority=1
stdout_logfile=/var/log/eq-trade-handler-service.log
stderr_logfile=/var/log/eq-trade-handler-service-error.log

[program:trade-receiver-service]
command=java -jar /app/trade-receiver-service.jar --server.port=8083 --spring.profiles.active=forex
autostart=true
autorestart=true
priority=2
startsecs=30
stdout_logfile=/var/log/trade-receiver-service.log
stderr_logfile=/var/log/trade-receiver-service-error.log
```

### Server 3 supervisord.conf

```ini
[supervisord]
nodaemon=true
logfile=/var/log/supervisord.log
pidfile=/var/run/supervisord.pid

[program:reference-lookup-service]
command=java -jar /app/reference-lookup-service.jar --server.port=8081
autostart=true
autorestart=true
priority=1
stdout_logfile=/var/log/reference-lookup-service.log
stderr_logfile=/var/log/reference-lookup-service-error.log

[program:check-eligible-service]
command=java -jar /app/check-eligible-service.jar --server.port=8092
autostart=true
autorestart=true
priority=1
stdout_logfile=/var/log/check-eligible-service.log
stderr_logfile=/var/log/check-eligible-service-error.log

[program:trade-receiver-service]
command=java -jar /app/trade-receiver-service.jar --server.port=8082 --spring.profiles.active=irswap
autostart=true
autorestart=true
priority=2
startsecs=30
stdout_logfile=/var/log/trade-receiver-service.log
stderr_logfile=/var/log/trade-receiver-service-error.log
```

### Server 4 supervisord.conf

```ini
[supervisord]
nodaemon=true
logfile=/var/log/supervisord.log
pidfile=/var/run/supervisord.pid

[program:eureka-server]
command=java -jar /app/eureka-server.jar --server.port=8088
autostart=true
autorestart=true
priority=1
stdout_logfile=/var/log/eureka-server.log
stderr_logfile=/var/log/eureka-server-error.log

[program:trade-receiver-service]
command=java -jar /app/trade-receiver-service.jar --server.port=8084 --spring.profiles.active=supporteventhandler
autostart=true
autorestart=true
priority=2
startsecs=30
stdout_logfile=/var/log/trade-receiver-service.log
stderr_logfile=/var/log/trade-receiver-service-error.log
```

## Docker Compose Files

### docker/server1/docker-compose.yml

```yaml
version: '3.8'

services:
  server1:
    build:
      context: ../..
      dockerfile: docker/server1/Dockerfile
    container_name: rdp-server1
    hostname: server1
    ports:
      - "8088:8088"   # Eureka
      - "8082:8082"   # Ingestion (equity)
      - "8081:8081"   # RefData
      - "8092:8092"   # Eligibility
    environment:
      - JAVA_OPTS=-Xmx512m -Xms256m
      - EUREKA_SERVER_URL=http://server1:8088/eureka,http://server4:8088/eureka
      - SERVER_ID=server1
    networks:
      - rdp-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8088/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 90s

networks:
  rdp-network:
    external: true
```

### docker/server2/docker-compose.yml

```yaml
version: '3.8'

services:
  server2:
    build:
      context: ../..
      dockerfile: docker/server2/Dockerfile
    container_name: rdp-server2
    hostname: server2
    ports:
      - "8090:8090"   # Regulatory
      - "8083:8083"   # Ingestion (forex)
    environment:
      - JAVA_OPTS=-Xmx512m -Xms256m
      - EUREKA_SERVER_URL=http://server1:8088/eureka,http://server4:8088/eureka
      - SERVER_ID=server2
    networks:
      - rdp-network
    depends_on:
      - server1
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8090/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s

networks:
  rdp-network:
    external: true
```

### docker/server3/docker-compose.yml

```yaml
version: '3.8'

services:
  server3:
    build:
      context: ../..
      dockerfile: docker/server3/Dockerfile
    container_name: rdp-server3
    hostname: server3
    ports:
      - "18081:8081"  # RefData (different host port to avoid conflict)
      - "18082:8082"  # Ingestion (irswap)
      - "18092:8092"  # Eligibility
    environment:
      - JAVA_OPTS=-Xmx512m -Xms256m
      - EUREKA_SERVER_URL=http://server1:8088/eureka,http://server4:8088/eureka
      - SERVER_ID=server3
    networks:
      - rdp-network
    depends_on:
      - server1
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s

networks:
  rdp-network:
    external: true
```

### docker/server4/docker-compose.yml

```yaml
version: '3.8'

services:
  server4:
    build:
      context: ../..
      dockerfile: docker/server4/Dockerfile
    container_name: rdp-server4
    hostname: server4
    ports:
      - "18088:8088"  # Eureka (different host port)
      - "8084:8084"   # Ingestion (supporteventhandler)
    environment:
      - JAVA_OPTS=-Xmx512m -Xms256m
      - EUREKA_SERVER_URL=http://server1:8088/eureka,http://server4:8088/eureka
      - SERVER_ID=server4
    networks:
      - rdp-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8088/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 90s

networks:
  rdp-network:
    external: true
```

### Full Stack docker-compose.yml

```yaml
version: '3.8'

services:
  server1:
    build:
      context: .
      dockerfile: docker/server1/Dockerfile
    container_name: rdp-server1
    hostname: server1
    ports:
      - "8088:8088"
      - "8082:8082"
      - "8081:8081"
      - "8092:8092"
    environment:
      - JAVA_OPTS=-Xmx512m -Xms256m
      - EUREKA_SERVER_URL=http://server1:8088/eureka,http://server4:8088/eureka
      - SERVER_ID=server1
    networks:
      - rdp-network

  server2:
    build:
      context: .
      dockerfile: docker/server2/Dockerfile
    container_name: rdp-server2
    hostname: server2
    ports:
      - "8090:8090"
      - "8083:8083"
    environment:
      - JAVA_OPTS=-Xmx512m -Xms256m
      - EUREKA_SERVER_URL=http://server1:8088/eureka,http://server4:8088/eureka
      - SERVER_ID=server2
    networks:
      - rdp-network
    depends_on:
      server1:
        condition: service_healthy

  server3:
    build:
      context: .
      dockerfile: docker/server3/Dockerfile
    container_name: rdp-server3
    hostname: server3
    ports:
      - "18081:8081"
      - "18082:8082"
      - "18092:8092"
    environment:
      - JAVA_OPTS=-Xmx512m -Xms256m
      - EUREKA_SERVER_URL=http://server1:8088/eureka,http://server4:8088/eureka
      - SERVER_ID=server3
    networks:
      - rdp-network
    depends_on:
      server1:
        condition: service_healthy

  server4:
    build:
      context: .
      dockerfile: docker/server4/Dockerfile
    container_name: rdp-server4
    hostname: server4
    ports:
      - "18088:8088"
      - "8084:8084"
    environment:
      - JAVA_OPTS=-Xmx512m -Xms256m
      - EUREKA_SERVER_URL=http://server1:8088/eureka,http://server4:8088/eureka
      - SERVER_ID=server4
    networks:
      - rdp-network

networks:
  rdp-network:
    driver: bridge
```

## Commands

### Create Network

```bash
docker network create rdp-network
```

### Build All Images

```bash
# Build project first
mvn clean package -DskipTests

# Build Docker images
docker-compose build
```

### Start Individual Servers

```bash
# Start Server 1 (with Eureka - start first)
docker-compose -f docker/server1/docker-compose.yml up -d

# Wait for Eureka to be ready
sleep 60

# Start Server 4 (second Eureka)
docker-compose -f docker/server4/docker-compose.yml up -d

# Start remaining servers
docker-compose -f docker/server2/docker-compose.yml up -d
docker-compose -f docker/server3/docker-compose.yml up -d
```

### Start Full Stack

```bash
docker-compose up -d
```

### View Logs

```bash
# All containers
docker-compose logs -f

# Specific server
docker logs -f rdp-server1
```

### Stop All

```bash
docker-compose down
```

## Resource Allocation

| Server | Memory | CPU |
|--------|--------|-----|
| Server 1 | 1GB | 1 |
| Server 2 | 768MB | 0.5 |
| Server 3 | 1GB | 1 |
| Server 4 | 768MB | 0.5 |

## Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `JAVA_OPTS` | JVM options | `-Xmx512m -Xms256m` |
| `EUREKA_SERVER_URL` | Eureka cluster URLs | `http://server1:8088/eureka,http://server4:8088/eureka` |
| `SERVER_ID` | Server identifier | `server1`, `server2`, etc. |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `equity`, `forex`, etc. |
