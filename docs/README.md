# RDP MSA Viability POC

A Spring Boot microservices proof-of-concept demonstrating service discovery, client-side load balancing with local-first routing, and resilience patterns.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Services](#services)
- [Quick Start](#quick-start)
- [Documentation](#documentation)
- [Development](#development)

## Overview

This POC implements a distributed microservices architecture with:

- **Service Discovery**: Netflix Eureka with High Availability (2 instances)
- **Client-Side Load Balancing**: Local-first routing strategy with load-aware failover
- **Resilience Patterns**: Retry with exponential backoff, Circuit Breaker
- **Background Health Monitoring**: ExecutorService-based health checks

### Key Features

1. **Local-First Load Balancing**: Services prefer calling local instances before remote
2. **Load-Aware Routing**: Routes requests based on service instance load
3. **Automatic Failover**: Seamless failover to healthy instances
4. **Thread-Safe Health Registry**: Background health monitoring with concurrent access

## Architecture

```
                                    ┌─────────────────────────────────────────────────────────┐
                                    │                    Docker Network                      │
                                    │                                                        │
   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐              │
   │   Server 1   │    │   Server 2   │    │   Server 3   │    │   Server 4   │              │
   │              │    │              │    │              │    │              │              │
   │ ┌──────────┐ │    │ ┌──────────┐ │    │ ┌──────────┐ │    │ ┌──────────┐ │              │
   │ │ Eureka   │ │    │ │Regulatory│ │    │ │ RefData  │ │    │ │ Eureka   │ │              │
   │ │ :8088    │◄├────┼─┤ :8090    │ │    │ │ :8081    │ │    │ │ :8088    │ │              │
   │ └──────────┘ │    │ └──────────┘ │    │ └──────────┘ │    │ └──────────┘ │              │
   │              │    │              │    │              │    │              │              │
   │ ┌──────────┐ │    │ ┌──────────┐ │    │ ┌──────────┐ │    │ ┌──────────┐ │              │
   │ │Ingestion │ │    │ │Ingestion │ │    │ │Ingestion │ │    │ │Ingestion │ │              │
   │ │(equity)  │ │    │ │(forex)   │ │    │ │(irswap)  │ │    │ │(support) │ │              │
   │ │ :8082    │ │    │ │ :8083    │ │    │ │ :8082    │ │    │ │ :8084    │ │              │
   │ └──────────┘ │    │ └──────────┘ │    │ └──────────┘ │    │ └──────────┘ │              │
   │              │    │              │    │              │    │              │              │
   │ ┌──────────┐ │    └──────────────┘    │ ┌──────────┐ │    └──────────────┘              │
   │ │ RefData  │ │                        │ │Eligiblty │ │                                  │
   │ │ :8081    │ │                        │ │ :8092    │ │                                  │
   │ └──────────┘ │                        │ └──────────┘ │                                  │
   │              │                        │              │                                  │
   │ ┌──────────┐ │                        └──────────────┘                                  │
   │ │Eligiblty │ │                                                                          │
   │ │ :8092    │ │                                                                          │
   │ └──────────┘ │                                                                          │
   └──────────────┘                                                                          │
                                    └─────────────────────────────────────────────────────────┘
```

## Services

| Service | Description | Port(s) | Servers |
|---------|-------------|---------|---------|
| **eureka-server** | Service Discovery | 8088 | 1, 4 |
| **ingestion-service** | Data Ingestion | 8082-8084 | 1, 2, 3, 4 |
| **regulatory-service** | Regulatory Processing | 8090 | 2 |
| **refdata-service** | Reference Data | 8081 | 1, 3 |
| **eligibility-service** | Eligibility Checking | 8092 | 1, 3 |

## Quick Start

### Prerequisites

- Java 21
- Maven 3.9+
- Docker & Docker Compose
- IntelliJ IDEA Ultimate (recommended)

### Build

```bash
# Build all modules
mvn clean install

# Build specific module
mvn clean install -pl <module-name>
```

### Run with Docker

```bash
# Start Server 1
docker-compose -f docker/server1/docker-compose.yml up -d

# Start Server 2
docker-compose -f docker/server2/docker-compose.yml up -d

# Start Server 3
docker-compose -f docker/server3/docker-compose.yml up -d

# Start Server 4
docker-compose -f docker/server4/docker-compose.yml up -d
```

### Run Locally (Development)

```bash
# Start Eureka Server
mvn spring-boot:run -pl eureka-server

# Start RefData Service
mvn spring-boot:run -pl refdata-service

# Start Eligibility Service
mvn spring-boot:run -pl eligibility-service

# Start Ingestion Service (with profile)
mvn spring-boot:run -pl ingestion-service -Dspring-boot.run.profiles=equity

# Start Regulatory Service
mvn spring-boot:run -pl regulatory-service
```

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture](architecture.md) | Microservice architecture overview, Service Instance Registry |
| [C4 Diagrams](c4-diagrams.md) | Context, Container, Component diagrams |
| [API Flow](api-flow.md) | API call flows with sequence diagrams |
| [Eureka Operations](eureka-operations.md) | Register, Heartbeat, Fetch, Deregister operations & Retry flow |
| [Docker Architecture](docker-architecture.md) | Docker setup and configuration |

## Development

### Project Structure

```
rdp-msa-viability-poc/
├── pom.xml                     # Parent POM
├── common/                     # Shared utilities
├── eureka-server/              # Service Discovery
├── refdata-service/            # Reference Data Service
├── eligibility-service/        # Eligibility Service
├── ingestion-service/          # Ingestion Service
├── regulatory-service/         # Regulatory Service
├── docker/                     # Docker configurations
│   ├── server1/
│   ├── server2/
│   ├── server3/
│   └── server4/
└── docs/                       # Documentation
```

### Tech Stack

- **Java 21**
- **Spring Boot 3.5.6**
- **Spring Cloud 2025.0.0**
  - Netflix Eureka (Service Discovery)
  - Spring Cloud LoadBalancer (Client-Side LB)
  - OpenFeign (Declarative REST Client)
- **Resilience4j** (Retry, Circuit Breaker)
- **Docker & Docker Compose**

### Endpoints

Each service exposes:

- `GET /actuator/health` - Health check
- `GET /actuator/info` - Service info
- Service-specific REST endpoints

### Configuration

Configuration is managed via `application.yml` files in each service module.

Key configurations:
- Eureka client settings
- Load balancer preferences
- Resilience4j retry/circuit breaker settings
- Background health check interval
