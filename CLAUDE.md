# RDP-MSA Viability POC - Project Context

## Overview

This is a Spring Boot microservices proof-of-concept demonstrating service discovery, client-side load balancing with local-first routing, and resilience patterns.

## Architecture

### Services

| Service | Description | Profiles |
|---------|-------------|----------|
| **ingestion-service** | Entry point for data ingestion, handles ~20 req/sec max | equity, forex, irswap, supporteventhandler |
| **regulatory-service** | Regulatory processing service | - |
| **refdata-service** | Reference data provider | - |
| **eligibility-service** | Eligibility checking service | - |
| **eureka-server** | Service discovery (Netflix Eureka) | - |

### Server Distribution

```
Server 1 (Docker)
├── refdata-service         :8081
├── ingestion-service       :8082  (profile: equity)
├── eligibility-service     :8092
└── eureka-server           :8088

Server 2 (Docker)
├── regulatory-service      :8090
└── ingestion-service       :8083  (profile: forex)

Server 3 (Docker)
├── refdata-service         :8081
├── ingestion-service       :8082  (profile: irswap)
└── eligibility-service     :8092

Server 4 (Docker)
├── ingestion-service       :8084  (profile: supporteventhandler)
└── eureka-server           :8088
```

### Service Communication

```
ingestion-service  ──────► refdata-service
ingestion-service  ──────► eligibility-service
regulatory-service ──────► refdata-service
regulatory-service ──────► eligibility-service
```

## Key Requirements

### 1. Service Discovery
- Eureka servers deployed on Server 1 and Server 4 for High Availability
- All services register with Eureka
- No Cloud Config server needed

### 2. Client-Side Load Balancing (Local-First Strategy)
- When ingestion/regulatory calls refdata/eligibility:
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
├── refdata-service/
│   └── pom.xml
├── eligibility-service/
│   └── pom.xml
├── ingestion-service/
│   └── pom.xml
├── regulatory-service/
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
│   └── docker-architecture.md
└── CLAUDE.md                        # This file
```

## Development Phases

### Phase 1: Design (Current)
- [ ] Multi-module Maven project skeleton
- [ ] C4 diagrams (Context, Container, Component)
- [ ] API flow diagrams with sequence
- [ ] Client Load Balancer design documentation
- [ ] Docker architecture documentation
- [ ] Dockerfile and docker-compose for each server

### Phase 2: Implementation (Pending Approval)
- [ ] Eureka server setup
- [ ] Service registration
- [ ] Local-first load balancer implementation
- [ ] Background health monitoring with ExecutorService
- [ ] Resilience4j integration (retry, circuit breaker)
- [ ] REST API implementations
- [ ] Docker containerization

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
