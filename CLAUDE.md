# RDP-MSA Viability POC - Project Context

## Overview

This is a Spring Boot microservices proof-of-concept demonstrating service discovery, client-side load balancing with local-first routing, and resilience patterns.

## Architecture

### Services

| Service | Description | Profiles |
|---------|-------------|----------|
| **trade-receiver-service** | Entry point for data trade-receiver, handles ~20 req/sec max | equity, forex, irswap, supporteventhandler |
| **eq-trade-handler-service** | Regulatory processing service | - |
| **reference-lookup-service** | Reference data provider | - |
| **check-eligible-service** | Eligibility checking service | - |
| **eureka-server** | Service discovery (Netflix Eureka) | - |

### Server Distribution

```
Server 1 (Docker)
├── reference-lookup-service         :8081
├── trade-receiver-service       :8082  (profile: equity)
├── check-eligible-service     :8092
└── eureka-server           :8088

Server 2 (Docker)
├── eq-trade-handler-service      :8090
└── trade-receiver-service       :8083  (profile: forex)

Server 3 (Docker)
├── reference-lookup-service         :8081
├── trade-receiver-service       :8082  (profile: irswap)
└── check-eligible-service     :8092

Server 4 (Docker)
├── trade-receiver-service       :8084  (profile: supporteventhandler)
└── eureka-server           :8088
```

### Service Communication

```
trade-receiver-service  ──────► reference-lookup-service
trade-receiver-service  ──────► check-eligible-service
eq-trade-handler-service ──────► reference-lookup-service
eq-trade-handler-service ──────► check-eligible-service
```

## Key Requirements

### 1. Service Discovery
- Eureka servers deployed on Server 1 and Server 4 for High Availability
- All services register with Eureka
- No Cloud Config server needed

### 2. Client-Side Load Balancing (Local-First Strategy)
- When trade-receiver/eq-trade-handler calls refdata/eligibility:
  1. First attempt: Call LOCAL instance (same server) if available
  2. Second attempt: If local fails, call REMOTE instance based on load
  3. Load-aware routing: Choose instance with lowest request load

### 3. Passive Health Pattern (Implemented)
Real-time failure detection with immediate failover:
- **Passive Health Updates**: Failed instances marked unhealthy immediately during requests
- **Immediate Retry**: 0ms delay between retry attempts (no exponential backoff)
- **Instance Skip**: Already-tried instances excluded from retry cycle via `PassiveHealthContext`
- **Resilience4j Integration**: `PassiveHealthRetryEventListener` listens to retry events

### 4. Resilience Patterns
- **Retry**: Immediate failover (0ms delay) across service instances
- **Circuit Breaker**: Prevent cascade failures (50% threshold, 30s open state)
- **Timeout Handling**: On timeout, failover to another instance immediately

### 5. Background Health Monitoring (Recovery Detection)
- ExecutorService thread running in background
- Configurable interval (default: 30 seconds) - focused on recovery detection
- Failure detection handled by passive health (real-time)
- Recovery detection handled by background monitor
- Thread-safe ServiceInstances health object with atomic updates

## Tech Stack

| Component | Version/Technology |
|-----------|-------------------|
| Java | 21 |
| Spring Boot | 3.5.6 |
| Spring Cloud | Compatible with Spring Boot 3.5.6 |
| Service Discovery | Netflix Eureka |
| Client Load Balancer | Spring Cloud LoadBalancer |
| REST Client | Feign Client / RestClient |
| Resilience | Resilience4j (retry, circuit breaker) |
| Build Tool | Maven (multi-module) |
| Containerization | Docker, Docker Compose |
| IDE | IntelliJ IDEA Ultimate |
| OS | Windows 11 |

## Project Structure

```
rdp-msa-viability-poc/
├── pom.xml                          # Parent POM
├── eureka-server/
│   └── pom.xml
├── reference-lookup-service/
│   └── pom.xml
├── check-eligible-service/
│   └── pom.xml
├── trade-receiver-service/
│   └── pom.xml
├── eq-trade-handler-service/
│   └── pom.xml
├── common/                          # Shared utilities
│   ├── pom.xml
│   └── src/main/java/.../common/
│       ├── loadbalancer/            # LocalFirstLoadBalancer
│       ├── registry/                # ServiceInstanceRegistry
│       ├── resilience/              # PassiveHealthContext, PassiveHealthRetryEventListener
│       ├── health/                  # HealthMonitorService
│       └── eureka/                  # EurekaRegistrySync
├── docker/
│   ├── server1/
│   │   └── docker-compose.yml
│   ├── server2/
│   │   └── docker-compose.yml
│   ├── server3/
│   │   └── docker-compose.yml
│   └── server4/
│       └── docker-compose.yml
├── docs/
│   ├── README.md
│   ├── architecture.md
│   ├── passive-health-retry-design.md  # Passive health pattern design
│   ├── c4-diagrams.md
│   ├── api-flow.md
│   ├── docker-architecture.md
│   ├── runbook.md                   # Operational runbook
│   └── testing-guide.md             # Testing guide
├── scripts/
│   └── test-resilience.sh           # Resilience testing script
└── CLAUDE.md                        # This file
```

## Development Phases

### Phase 1: Design (Complete)
- [x] Multi-module Maven project skeleton
- [x] C4 diagrams (Context, Container, Component)
- [x] API flow diagrams with sequence
- [x] Client Load Balancer design documentation
- [x] Docker architecture documentation
- [x] Dockerfile and docker-compose for each server

### Phase 2: Implementation (Complete)
- [x] Eureka server setup
- [x] Service registration
- [x] Local-first load balancer implementation
- [x] Background health monitoring with ExecutorService
- [x] Resilience4j integration (retry, circuit breaker)
- [x] REST API implementations
- [x] Docker containerization
- [x] **Passive Health Pattern** (immediate failover, real-time failure detection)

### Phase 3: Testing & Validation (Complete)
- [x] Build and verify all services compile
- [x] Deploy to Docker environment
- [x] Verify service registration with Eureka
- [x] Test passive health pattern (immediate failover) - **VERIFIED**
- [x] Test resilience patterns (retry, circuit breaker) - **VERIFIED**
- [x] Test local-first load balancing - **VERIFIED**
- [ ] Performance testing (~20 req/sec)

## Commands

```bash
# Build all modules
mvn clean install

# Run specific service
mvn spring-boot:run -pl <module-name>

# Docker compose per server
docker-compose -f docker/server1/docker-compose.yml up -d
```

## Passive Health Pattern

### How It Works

```
Request → Local Instance → FAIL
    ↓
  Mark local UNHEALTHY (passive health)
  Add to tried set (PassiveHealthContext)
    ↓
Retry (0ms) → Remote Instance → SUCCESS
    ↓
Clear context, return response
```

### Key Components

| Component | Location | Purpose |
|-----------|----------|---------|
| `PassiveHealthContext` | common/.../resilience/ | Thread-local context tracking tried instances |
| `PassiveHealthRetryEventListener` | common/.../resilience/ | Listens to Resilience4j events, marks instances unhealthy |
| `LocalFirstLoadBalancer` | common/.../loadbalancer/ | Skips tried instances, tracks current instance |
| `ServiceInstanceRegistry` | common/.../registry/ | `markUnhealthy()`, `markHealthy()` with logging |

### Configuration

```yaml
# Resilience4j - Immediate Failover
resilience4j:
  retry:
    instances:
      referenceLookup:
        max-attempts: 3
        wait-duration: 0ms              # Immediate retry
        enable-exponential-backoff: false

# Health Monitor - Recovery Detection Only
health:
  monitor:
    interval: 30                        # 30 seconds (failures detected real-time)
```

### Benefits
- **Failover latency**: ~100ms (vs 3+ seconds with traditional approach)
- **Real-time failure detection**: During actual requests, not background polling
- **No stale cache**: Failed instances immediately marked unhealthy

### Verified Test Results

| Test | Result | Key Metrics |
|------|--------|-------------|
| Passive Health Failover | ✅ PASS | Local fails → immediate retry to remote (~22ms after timeout) |
| 0ms Retry Delay | ✅ PASS | No delay between retry attempts |
| Instance Skip | ✅ PASS | Already-tried instances excluded from retry cycle |
| Circuit Breaker Open | ✅ PASS | Opens after 50% failure rate (100% in test) |
| Fast-Fail Response | ✅ PASS | 67ms with fallback data (vs ~20s without CB) |
| Circuit Recovery | ✅ PASS | OPEN → HALF_OPEN (30s) → CLOSED (3 probes) |
| Local-First Preference | ✅ PASS | Local instance selected first when healthy |
| Remote Failover | ✅ PASS | Remote selected when local unavailable |
| Recovery Detection | ✅ PASS | Background monitor marks recovered instances healthy |

## Notes

- All servers are in the same Docker network
- Each server is a single Docker container running multiple services
- Health checks should be lightweight to minimize overhead
- Thread-safe design required for shared ServiceInstances object
- Passive health updates use WARN level logging for visibility
