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

### 3. Resilience Patterns
- **Retry**: Exponential backoff across service instances (local/remote)
- **Circuit Breaker**: Prevent cascade failures
- **Timeout Handling**: On timeout, failover to another instance based on availability

### 4. Background Health Monitoring
- ExecutorService thread running in background
- Configurable interval (default: 10 seconds)
- Updates thread-safe ServiceInstances health object
- Producer: Health check thread updates instance status
- Consumer: REST client uses health data for routing decisions

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
│   └── pom.xml
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

### Phase 3: Testing & Validation (Current)
- [ ] Build and verify all services compile
- [ ] Deploy to Docker environment
- [ ] Verify service registration with Eureka
- [ ] Test resilience patterns (retry, circuit breaker)
- [ ] Test local-first load balancing
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

## Notes

- All servers are in the same Docker network
- Each server is a single Docker container running multiple services
- Health checks should be lightweight to minimize overhead
- Thread-safe design required for shared ServiceInstances object
