# Microservice Architecture

## Overview

This document describes the microservice architecture for the RDP MSA Viability POC.

## System Context

The system consists of 5 core services distributed across 4 servers (Docker containers) within the same network.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              RDP MSA System                                  │
│                                                                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │   Eureka    │  │  Ingestion  │  │  RefData    │  │    Eligibility      │ │
│  │   Server    │  │   Service   │  │  Service    │  │      Service        │ │
│  │    (HA)     │  │ (4 profiles)│  │ (2 inst.)   │  │    (2 inst.)        │ │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────────────┘ │
│                                                                              │
│                          ┌─────────────┐                                     │
│                          │ Regulatory  │                                     │
│                          │  Service    │                                     │
│                          └─────────────┘                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Service Descriptions

### 1. Eureka Server (Service Discovery)

**Purpose**: Provides service registration and discovery for all microservices.

**Deployment**: High Availability setup with 2 instances
- Server 1: Port 8088
- Server 4: Port 8088

**Responsibilities**:
- Maintain registry of all service instances
- Provide service instance information to clients
- Health monitoring of registered services
- Peer replication for HA

### 2. Ingestion Service

**Purpose**: Entry point for data ingestion into the system.

**Deployment**: 4 instances with different profiles
- Server 1: Port 8082 (profile: equity)
- Server 2: Port 8083 (profile: forex)
- Server 3: Port 8082 (profile: irswap)
- Server 4: Port 8084 (profile: supporteventhandler)

**Responsibilities**:
- Accept incoming data (up to 20 req/sec)
- Call RefData service for reference data
- Call Eligibility service for eligibility checks
- Implement local-first load balancing
- Handle resilience (retry, circuit breaker)

**Dependencies**:
- RefData Service
- Eligibility Service

### 3. Regulatory Service

**Purpose**: Handles regulatory processing and compliance.

**Deployment**: Single instance
- Server 2: Port 8090

**Responsibilities**:
- Process regulatory requirements
- Call RefData service for reference data
- Call Eligibility service for eligibility checks
- Implement local-first load balancing
- Handle resilience (retry, circuit breaker)

**Dependencies**:
- RefData Service
- Eligibility Service

### 4. RefData Service

**Purpose**: Provides reference data to other services.

**Deployment**: 2 instances
- Server 1: Port 8081
- Server 3: Port 8081

**Responsibilities**:
- Serve reference data requests
- Register with Eureka
- Expose health endpoints

### 5. Eligibility Service

**Purpose**: Performs eligibility checking for transactions.

**Deployment**: 2 instances
- Server 1: Port 8092
- Server 3: Port 8092

**Responsibilities**:
- Process eligibility check requests
- Register with Eureka
- Expose health endpoints

## Server Distribution Matrix

| Server | Eureka | Ingestion | Regulatory | RefData | Eligibility |
|--------|--------|-----------|------------|---------|-------------|
| Server 1 | :8088 | :8082 (equity) | - | :8081 | :8092 |
| Server 2 | - | :8083 (forex) | :8090 | - | - |
| Server 3 | - | :8082 (irswap) | - | :8081 | :8092 |
| Server 4 | :8088 | :8084 (support) | - | - | - |

## Communication Patterns

### Service-to-Service Communication

```
┌──────────────────┐         ┌─────────────────┐
│                  │         │                 │
│    Ingestion     │────────►│    RefData      │
│    Service       │         │    Service      │
│                  │────┐    │                 │
└──────────────────┘    │    └─────────────────┘
                        │
                        │    ┌─────────────────┐
                        │    │                 │
                        └───►│   Eligibility   │
                             │    Service      │
                             │                 │
                             └─────────────────┘

┌──────────────────┐         ┌─────────────────┐
│                  │         │                 │
│   Regulatory     │────────►│    RefData      │
│    Service       │         │    Service      │
│                  │────┐    │                 │
└──────────────────┘    │    └─────────────────┘
                        │
                        │    ┌─────────────────┐
                        │    │                 │
                        └───►│   Eligibility   │
                             │    Service      │
                             │                 │
                             └─────────────────┘
```

### Service Discovery Flow

```
┌─────────────┐      1. Register       ┌─────────────┐
│   Service   │ ─────────────────────► │   Eureka    │
│  Instance   │                        │   Server    │
└─────────────┘ ◄───────────────────── └─────────────┘
                   2. Heartbeat

┌─────────────┐      3. Discover       ┌─────────────┐
│   Client    │ ─────────────────────► │   Eureka    │
│   Service   │                        │   Server    │
└─────────────┘ ◄───────────────────── └─────────────┘
                 4. Service Instances
```

### Eureka Service Instance Registry

The Eureka Server maintains a registry of all service instances with their metadata. Below is a detailed view of the ServiceInstanceRegistry structure:

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                                 │
│                           EUREKA SERVICE INSTANCE REGISTRY                                      │
│                                                                                                 │
│  ┌───────────────────────────────────────────────────────────────────────────────────────────┐  │
│  │                                                                                           │  │
│  │   Registry: ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>>                  │  │
│  │                                                                                           │  │
│  └───────────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                                 │
│  ┌───────────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  SERVICE: REFDATA-SERVICE                                                                 │  │
│  │  ─────────────────────────────────────────────────────────────────────────────────────── │  │
│  │                                                                                           │  │
│  │  ┌─────────────────────────────────────────┐  ┌─────────────────────────────────────────┐│  │
│  │  │  Instance: refdata-service-server1      │  │  Instance: refdata-service-server3      ││  │
│  │  │  ─────────────────────────────────────  │  │  ─────────────────────────────────────  ││  │
│  │  │  instanceId: server1:refdata:8081       │  │  instanceId: server3:refdata:8081       ││  │
│  │  │  hostName:   server1                    │  │  hostName:   server3                    ││  │
│  │  │  ipAddr:     172.18.0.2                 │  │  ipAddr:     172.18.0.4                 ││  │
│  │  │  port:       8081                       │  │  port:       8081                       ││  │
│  │  │  status:     UP                         │  │  status:     UP                         ││  │
│  │  │  healthCheckUrl:                        │  │  healthCheckUrl:                        ││  │
│  │  │    http://server1:8081/actuator/health  │  │    http://server3:8081/actuator/health  ││  │
│  │  │  lastUpdatedTimestamp: 1704067200000    │  │  lastUpdatedTimestamp: 1704067200000    ││  │
│  │  │  lastDirtyTimestamp:   1704067180000    │  │  lastDirtyTimestamp:   1704067180000    ││  │
│  │  │  metadata:                              │  │  metadata:                              ││  │
│  │  │    server-id: server1                   │  │    server-id: server3                   ││  │
│  │  │    zone: default                        │  │    zone: default                        ││  │
│  │  └─────────────────────────────────────────┘  └─────────────────────────────────────────┘│  │
│  │                                                                                           │  │
│  └───────────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                                 │
│  ┌───────────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  SERVICE: ELIGIBILITY-SERVICE                                                             │  │
│  │  ─────────────────────────────────────────────────────────────────────────────────────── │  │
│  │                                                                                           │  │
│  │  ┌─────────────────────────────────────────┐  ┌─────────────────────────────────────────┐│  │
│  │  │  Instance: eligibility-service-server1  │  │  Instance: eligibility-service-server3  ││  │
│  │  │  ─────────────────────────────────────  │  │  ─────────────────────────────────────  ││  │
│  │  │  instanceId: server1:eligibility:8092   │  │  instanceId: server3:eligibility:8092   ││  │
│  │  │  hostName:   server1                    │  │  hostName:   server3                    ││  │
│  │  │  ipAddr:     172.18.0.2                 │  │  ipAddr:     172.18.0.4                 ││  │
│  │  │  port:       8092                       │  │  port:       8092                       ││  │
│  │  │  status:     UP                         │  │  status:     UP                         ││  │
│  │  │  healthCheckUrl:                        │  │  healthCheckUrl:                        ││  │
│  │  │    http://server1:8092/actuator/health  │  │    http://server3:8092/actuator/health  ││  │
│  │  │  lastUpdatedTimestamp: 1704067200000    │  │  lastUpdatedTimestamp: 1704067200000    ││  │
│  │  │  lastDirtyTimestamp:   1704067180000    │  │  lastDirtyTimestamp:   1704067180000    ││  │
│  │  │  metadata:                              │  │  metadata:                              ││  │
│  │  │    server-id: server1                   │  │    server-id: server3                   ││  │
│  │  │    zone: default                        │  │    zone: default                        ││  │
│  │  └─────────────────────────────────────────┘  └─────────────────────────────────────────┘│  │
│  │                                                                                           │  │
│  └───────────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                                 │
│  ┌───────────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  SERVICE: INGESTION-SERVICE                                                               │  │
│  │  ─────────────────────────────────────────────────────────────────────────────────────── │  │
│  │                                                                                           │  │
│  │  ┌──────────────────────────────────┐  ┌──────────────────────────────────┐              │  │
│  │  │  Instance: ingestion-server1     │  │  Instance: ingestion-server2     │              │  │
│  │  │  ────────────────────────────    │  │  ────────────────────────────    │              │  │
│  │  │  instanceId: server1:ing:8082    │  │  instanceId: server2:ing:8083    │              │  │
│  │  │  hostName:   server1             │  │  hostName:   server2             │              │  │
│  │  │  ipAddr:     172.18.0.2          │  │  ipAddr:     172.18.0.3          │              │  │
│  │  │  port:       8082                │  │  port:       8083                │              │  │
│  │  │  status:     UP                  │  │  status:     UP                  │              │  │
│  │  │  metadata:                       │  │  metadata:                       │              │  │
│  │  │    profile: equity               │  │    profile: forex                │              │  │
│  │  │    server-id: server1            │  │    server-id: server2            │              │  │
│  │  └──────────────────────────────────┘  └──────────────────────────────────┘              │  │
│  │                                                                                           │  │
│  │  ┌──────────────────────────────────┐  ┌──────────────────────────────────┐              │  │
│  │  │  Instance: ingestion-server3     │  │  Instance: ingestion-server4     │              │  │
│  │  │  ────────────────────────────    │  │  ────────────────────────────    │              │  │
│  │  │  instanceId: server3:ing:8082    │  │  instanceId: server4:ing:8084    │              │  │
│  │  │  hostName:   server3             │  │  hostName:   server4             │              │  │
│  │  │  ipAddr:     172.18.0.4          │  │  ipAddr:     172.18.0.5          │              │  │
│  │  │  port:       8082                │  │  port:       8084                │              │  │
│  │  │  status:     UP                  │  │  status:     UP                  │              │  │
│  │  │  metadata:                       │  │  metadata:                       │              │  │
│  │  │    profile: irswap               │  │    profile: supporteventhandler  │              │  │
│  │  │    server-id: server3            │  │    server-id: server4            │              │  │
│  │  └──────────────────────────────────┘  └──────────────────────────────────┘              │  │
│  │                                                                                           │  │
│  └───────────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                                 │
│  ┌───────────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  SERVICE: REGULATORY-SERVICE                                                              │  │
│  │  ─────────────────────────────────────────────────────────────────────────────────────── │  │
│  │                                                                                           │  │
│  │  ┌─────────────────────────────────────────┐                                             │  │
│  │  │  Instance: regulatory-service-server2   │                                             │  │
│  │  │  ─────────────────────────────────────  │                                             │  │
│  │  │  instanceId: server2:regulatory:8090    │                                             │  │
│  │  │  hostName:   server2                    │                                             │  │
│  │  │  ipAddr:     172.18.0.3                 │                                             │  │
│  │  │  port:       8090                       │                                             │  │
│  │  │  status:     UP                         │                                             │  │
│  │  │  healthCheckUrl:                        │                                             │  │
│  │  │    http://server2:8090/actuator/health  │                                             │  │
│  │  │  lastUpdatedTimestamp: 1704067200000    │                                             │  │
│  │  │  lastDirtyTimestamp:   1704067180000    │                                             │  │
│  │  │  metadata:                              │                                             │  │
│  │  │    server-id: server2                   │                                             │  │
│  │  │    zone: default                        │                                             │  │
│  │  └─────────────────────────────────────────┘                                             │  │
│  │                                                                                           │  │
│  └───────────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Instance Info Data Model

Each registered service instance contains the following information:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           InstanceInfo                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Core Identification                                                         │
│  ───────────────────                                                         │
│  ├── instanceId        : Unique identifier (e.g., "server1:refdata:8081")   │
│  ├── appName           : Application name (e.g., "REFDATA-SERVICE")         │
│  ├── appGroupName      : Application group (optional)                       │
│  └── vipAddress        : Virtual IP address for load balancing              │
│                                                                              │
│  Network Information                                                         │
│  ───────────────────                                                         │
│  ├── hostName          : Host name (e.g., "server1")                        │
│  ├── ipAddr            : IP address (e.g., "172.18.0.2")                    │
│  ├── port              : Service port (e.g., 8081)                          │
│  ├── securePort        : HTTPS port (if enabled)                            │
│  └── homePageUrl       : Base URL of the service                            │
│                                                                              │
│  Health & Status                                                             │
│  ───────────────                                                             │
│  ├── status            : UP | DOWN | STARTING | OUT_OF_SERVICE | UNKNOWN    │
│  ├── overriddenStatus  : Admin override status                              │
│  ├── healthCheckUrl    : Health endpoint URL                                │
│  ├── statusPageUrl     : Status/info endpoint URL                           │
│  └── isCoordinatingDiscoveryServer : Is this instance a Eureka server?     │
│                                                                              │
│  Timestamps                                                                  │
│  ──────────                                                                  │
│  ├── lastUpdatedTimestamp : Last heartbeat received                         │
│  ├── lastDirtyTimestamp   : Last state change                               │
│  └── registrationTimestamp: When instance first registered                  │
│                                                                              │
│  Lease Information                                                           │
│  ─────────────────                                                           │
│  ├── renewalIntervalInSecs    : Heartbeat interval (default: 30s)          │
│  ├── durationInSecs           : Lease expiry time (default: 90s)           │
│  └── evictionTimestamp        : When instance was evicted (if applicable)  │
│                                                                              │
│  Custom Metadata                                                             │
│  ───────────────                                                             │
│  └── metadata: Map<String, String>                                          │
│      ├── server-id      : "server1" | "server2" | "server3" | "server4"    │
│      ├── profile        : "equity" | "forex" | "irswap" | "support"        │
│      ├── zone           : "default"                                         │
│      └── (custom keys)  : Application-specific metadata                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Registry Operations

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                              │
│                        Eureka Registry Operations                            │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │  REGISTER                                                              │  │
│  │  ────────                                                              │  │
│  │  POST /eureka/apps/{appName}                                          │  │
│  │                                                                        │  │
│  │  Service Instance ──────► Eureka Server                               │  │
│  │       │                         │                                      │  │
│  │       │  InstanceInfo JSON      │                                      │  │
│  │       │─────────────────────────►│                                      │  │
│  │       │                         │  Add to registry                     │  │
│  │       │        204 No Content   │  Replicate to peers                  │  │
│  │       │◄─────────────────────────│                                      │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │  HEARTBEAT (Renew)                                                     │  │
│  │  ─────────────────                                                     │  │
│  │  PUT /eureka/apps/{appName}/{instanceId}                              │  │
│  │                                                                        │  │
│  │  Service Instance ──────► Eureka Server                               │  │
│  │       │                         │                                      │  │
│  │       │  Every 30 seconds       │                                      │  │
│  │       │─────────────────────────►│                                      │  │
│  │       │                         │  Update lastUpdatedTimestamp        │  │
│  │       │        200 OK           │  Reset lease expiry                  │  │
│  │       │◄─────────────────────────│                                      │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │  FETCH REGISTRY                                                        │  │
│  │  ──────────────                                                        │  │
│  │  GET /eureka/apps                         (Full registry)             │  │
│  │  GET /eureka/apps/{appName}               (Single service)            │  │
│  │  GET /eureka/apps/delta                   (Incremental updates)       │  │
│  │                                                                        │  │
│  │  Client Service ──────► Eureka Server                                 │  │
│  │       │                         │                                      │  │
│  │       │  GET /eureka/apps       │                                      │  │
│  │       │─────────────────────────►│                                      │  │
│  │       │                         │                                      │  │
│  │       │  Applications JSON      │                                      │  │
│  │       │◄─────────────────────────│                                      │  │
│  │       │                         │                                      │  │
│  │  Client caches instances locally for load balancing                   │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │  DEREGISTER                                                            │  │
│  │  ──────────                                                            │  │
│  │  DELETE /eureka/apps/{appName}/{instanceId}                           │  │
│  │                                                                        │  │
│  │  Service Instance ──────► Eureka Server                               │  │
│  │       │                         │                                      │  │
│  │       │  On graceful shutdown   │                                      │  │
│  │       │─────────────────────────►│                                      │  │
│  │       │                         │  Remove from registry               │  │
│  │       │        200 OK           │  Replicate to peers                  │  │
│  │       │◄─────────────────────────│                                      │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## High Availability Design

### Eureka HA Configuration

```
┌─────────────┐                     ┌─────────────┐
│   Eureka    │◄───────────────────►│   Eureka    │
│  Server 1   │   Peer Replication  │  Server 4   │
│  :8088      │                     │  :8088      │
└─────────────┘                     └─────────────┘
       ▲                                   ▲
       │          ┌─────────────┐          │
       └──────────│   Services  │──────────┘
                  │  Register   │
                  │  to Both    │
                  └─────────────┘
```

### Service Redundancy

- **RefData Service**: 2 instances (Server 1, Server 3)
- **Eligibility Service**: 2 instances (Server 1, Server 3)
- **Ingestion Service**: 4 instances (different profiles, all servers)

## Network Architecture

All servers operate within the same Docker network, enabling:
- Direct service-to-service communication
- Service discovery via Eureka
- Load balancing across instances

```
┌─────────────────────────────────────────────────────────┐
│                   Docker Network: rdp-network           │
│                                                          │
│   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────┐ │
│   │ Server 1 │  │ Server 2 │  │ Server 3 │  │Server 4│ │
│   │          │  │          │  │          │  │        │ │
│   └──────────┘  └──────────┘  └──────────┘  └────────┘ │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

## Non-Functional Requirements

### Performance
- Ingestion service handles up to 20 requests/second
- Local-first routing minimizes network latency

### Reliability
- Eureka HA ensures service discovery availability
- Retry and circuit breaker patterns prevent cascade failures

### Scalability
- Horizontal scaling via additional service instances
- Load-aware routing distributes traffic efficiently

### Observability
- Health endpoints on all services
- Background health monitoring
- Actuator metrics exposure
