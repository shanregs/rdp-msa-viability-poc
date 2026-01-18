# RDP-MSA Viability POC - Operational Runbook

## Prerequisites Checklist

Before deploying the services, ensure the following are installed and configured:

### Required Software
- [ ] Java 21 (JDK for building, JRE for runtime)
- [ ] Maven 3.9+
- [ ] Docker 24.0+
- [ ] Docker Compose 2.20+

### Verification Commands
```bash
# Check Java version
java -version
# Expected: openjdk version "21.x.x"

# Check Maven version
mvn -version
# Expected: Apache Maven 3.9.x

# Check Docker version
docker --version
# Expected: Docker version 24.x.x

# Check Docker Compose version
docker compose version
# Expected: Docker Compose version v2.20.x
```

### Network Requirements
- Ports 8081-8092 available for services
- Ports 8088 available for Eureka servers
- Docker network `rdp-network` will be created automatically

---

## Build Commands

### Build All Modules
```bash
# From project root directory
cd /path/to/rdp-msa-viability-poc

# Clean and build all modules
mvn clean install

# Build without tests (faster)
mvn clean install -DskipTests

# Build specific module
mvn clean install -pl eureka-server
mvn clean install -pl reference-lookup-service
mvn clean install -pl check-eligible-service
mvn clean install -pl trade-receiver-service
mvn clean install -pl eq-trade-handler-service
mvn clean install -pl common
```

### Build Docker Images
```bash
# Build all server images from project root
docker build -t rdp-server1:latest -f docker/server1/Dockerfile .
docker build -t rdp-server2:latest -f docker/server2/Dockerfile .
docker build -t rdp-server3:latest -f docker/server3/Dockerfile .
docker build -t rdp-server4:latest -f docker/server4/Dockerfile .
```

---

## Deployment Sequence

### Step 1: Create Docker Network
```bash
docker network create rdp-network
```

### Step 2: Start Eureka Servers First (Servers 1 and 4)
Eureka servers must be running before other services can register.

```bash
# Start Server 1 (includes Eureka)
docker compose -f docker/server1/docker-compose.yml up -d

# Start Server 4 (includes Eureka)
docker compose -f docker/server4/docker-compose.yml up -d

# Wait for Eureka to be ready (about 30-60 seconds)
sleep 60
```

### Step 3: Verify Eureka is Running
```bash
# Check Eureka on Server 1
curl -s http://localhost:8088/actuator/health | jq .

# Check Eureka on Server 4
curl -s http://server4:8088/actuator/health | jq .

# View Eureka dashboard (in browser)
# http://localhost:8088
```

### Step 4: Start Remaining Servers
```bash
# Start Server 2 (eq-trade-handler-service, trade-receiver forex)
docker compose -f docker/server2/docker-compose.yml up -d

# Start Server 3 (reference-lookup, trade-receiver irswap, check-eligible)
docker compose -f docker/server3/docker-compose.yml up -d
```

### Step 5: Verify All Services Registered
```bash
# Check registered instances in Eureka
curl -s http://localhost:8088/eureka/apps | grep -E "<app>|<instanceId>"
```

---

## Health Verification Endpoints

### Service Health Checks
| Service | Server | Health Endpoint |
|---------|--------|-----------------|
| Eureka Server | Server 1 | `http://localhost:8088/actuator/health` |
| Reference Lookup | Server 1 | `http://localhost:8081/actuator/health` |
| Check Eligible | Server 1 | `http://localhost:8092/actuator/health` |
| Trade Receiver (equity) | Server 1 | `http://localhost:8082/actuator/health` |
| EQ Trade Handler | Server 2 | `http://server2:8090/actuator/health` |
| Trade Receiver (forex) | Server 2 | `http://server2:8083/actuator/health` |
| Reference Lookup | Server 3 | `http://server3:8081/actuator/health` |
| Trade Receiver (irswap) | Server 3 | `http://server3:8082/actuator/health` |
| Check Eligible | Server 3 | `http://server3:8092/actuator/health` |
| Eureka Server | Server 4 | `http://server4:8088/actuator/health` |
| Trade Receiver (supporteventhandler) | Server 4 | `http://server4:8084/actuator/health` |

### Batch Health Check Script
```bash
#!/bin/bash
ENDPOINTS=(
    "http://localhost:8088/actuator/health"
    "http://localhost:8081/actuator/health"
    "http://localhost:8082/actuator/health"
    "http://localhost:8092/actuator/health"
)

for endpoint in "${ENDPOINTS[@]}"; do
    status=$(curl -s -o /dev/null -w "%{http_code}" "$endpoint")
    if [ "$status" = "200" ]; then
        echo "✓ $endpoint - OK"
    else
        echo "✗ $endpoint - FAILED ($status)"
    fi
done
```

### Actuator Endpoints Available
- `/actuator/health` - Service health status
- `/actuator/info` - Service information
- `/actuator/circuitbreakers` - Circuit breaker status
- `/actuator/retries` - Retry metrics
- `/actuator/metrics` - All metrics

---

## Troubleshooting Guide

### Problem: Service fails to register with Eureka

**Symptoms:**
- Service starts but not visible in Eureka dashboard
- Logs show "Connection refused" to Eureka

**Solutions:**
1. Verify Eureka is running:
   ```bash
   curl http://localhost:8088/actuator/health
   ```

2. Check service Eureka configuration:
   ```yaml
   eureka:
     client:
       service-url:
         defaultZone: http://server1:8088/eureka/,http://server4:8088/eureka/
   ```

3. Verify network connectivity:
   ```bash
   docker exec <container> ping server1
   ```

---

### Problem: Circuit breaker is OPEN

**Symptoms:**
- Requests failing immediately
- Logs show "CircuitBreaker 'xxx' is OPEN"

**Solutions:**
1. Check circuit breaker status:
   ```bash
   curl http://localhost:8082/actuator/circuitbreakers
   ```

2. Check dependent service health:
   ```bash
   curl http://localhost:8081/actuator/health  # reference-lookup
   curl http://localhost:8092/actuator/health  # check-eligible
   ```

3. Wait for circuit breaker to transition to HALF_OPEN (default: 30 seconds)

4. Force reset (if needed):
   ```bash
   # Restart the affected service
   docker compose -f docker/server1/docker-compose.yml restart trade-receiver-service
   ```

---

### Problem: Local-first routing not working

**Symptoms:**
- Service always calling remote instance
- Logs don't show "Using local instance"

**Solutions:**
1. Verify server.id is set correctly:
   ```bash
   docker exec <container> env | grep SERVER_ID
   ```

2. Check service metadata in Eureka:
   ```bash
   curl http://localhost:8088/eureka/apps/REFERENCE-LOOKUP-SERVICE
   ```

3. Verify LocalFirstLoadBalancer is configured:
   - Check logs for "LocalFirstLoadBalancer" initialization

---

### Problem: Container fails to start

**Symptoms:**
- `docker compose up` shows exit code 1
- Service restarts repeatedly

**Solutions:**
1. Check container logs:
   ```bash
   docker logs <container_name>
   ```

2. Check supervisor logs inside container:
   ```bash
   docker exec <container> cat /var/log/supervisord.log
   docker exec <container> cat /var/log/<service>-error.log
   ```

3. Verify JAR files exist:
   ```bash
   docker exec <container> ls -la /app/
   ```

4. Check Java heap memory:
   ```bash
   # Add to docker-compose.yml environment
   JAVA_OPTS: "-Xms256m -Xmx512m"
   ```

---

### Problem: Out of memory errors

**Symptoms:**
- Services crashing with OOM errors
- Container killed by Docker

**Solutions:**
1. Increase container memory limits in docker-compose.yml:
   ```yaml
   services:
     server1:
       deploy:
         resources:
           limits:
             memory: 2G
   ```

2. Tune JVM heap per service:
   ```bash
   JAVA_OPTS="-Xms256m -Xmx512m"
   ```

---

## Shutdown Procedure

### Graceful Shutdown (Recommended)
Stop services in reverse order of startup:

```bash
# Step 1: Stop application servers first
docker compose -f docker/server2/docker-compose.yml down
docker compose -f docker/server3/docker-compose.yml down

# Step 2: Wait for services to deregister from Eureka (30 seconds)
sleep 30

# Step 3: Stop Eureka servers
docker compose -f docker/server4/docker-compose.yml down
docker compose -f docker/server1/docker-compose.yml down

# Step 4: Optionally remove network
docker network rm rdp-network
```

### Emergency Shutdown
```bash
# Stop all containers immediately
docker compose -f docker/server1/docker-compose.yml down
docker compose -f docker/server2/docker-compose.yml down
docker compose -f docker/server3/docker-compose.yml down
docker compose -f docker/server4/docker-compose.yml down

# Or stop all at once (less graceful)
docker stop $(docker ps -q --filter "network=rdp-network")
```

### Cleanup Commands
```bash
# Remove all stopped containers
docker container prune

# Remove unused images
docker image prune

# Remove all project images
docker rmi rdp-server1:latest rdp-server2:latest rdp-server3:latest rdp-server4:latest

# Remove network
docker network rm rdp-network

# Full cleanup (warning: removes all Docker resources)
docker system prune -a
```

---

## Log Locations

### Inside Containers
| Log File | Description |
|----------|-------------|
| `/var/log/supervisord.log` | Supervisor daemon logs |
| `/var/log/eureka-server.log` | Eureka server stdout |
| `/var/log/eureka-server-error.log` | Eureka server stderr |
| `/var/log/reference-lookup-service.log` | Reference lookup stdout |
| `/var/log/trade-receiver-service.log` | Trade receiver stdout |
| `/var/log/check-eligible-service.log` | Check eligible stdout |
| `/var/log/eq-trade-handler-service.log` | EQ trade handler stdout |

### View Logs
```bash
# View all logs for a container
docker logs <container_name>

# Follow logs in real-time
docker logs -f <container_name>

# View specific service log inside container
docker exec <container> tail -f /var/log/reference-lookup-service.log
```

---

## Quick Reference

### Common Commands
```bash
# View running containers
docker ps

# View container resource usage
docker stats

# Enter container shell
docker exec -it <container> /bin/bash

# Restart a service
docker compose -f docker/server1/docker-compose.yml restart

# View Eureka registered services
curl -s http://localhost:8088/eureka/apps | grep -E "<app>"
```

### Port Mapping Summary
| Port | Service | Server |
|------|---------|--------|
| 8081 | Reference Lookup Service | Server 1, Server 3 |
| 8082 | Trade Receiver (equity/irswap) | Server 1, Server 3 |
| 8083 | Trade Receiver (forex) | Server 2 |
| 8084 | Trade Receiver (supporteventhandler) | Server 4 |
| 8088 | Eureka Server | Server 1, Server 4 |
| 8090 | EQ Trade Handler Service | Server 2 |
| 8092 | Check Eligible Service | Server 1, Server 3 |
