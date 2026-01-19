# C4 Diagrams

This document contains C4 model diagrams for the RDP MSA Viability POC system.

## Level 1: System Context Diagram

Shows the system scope and its interactions with external entities.

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                                                                                  │
│                              System Context                                      │
│                                                                                  │
│                                                                                  │
│           ┌─────────────┐                                                        │
│           │             │                                                        │
│           │   External  │                                                        │
│           │   Client    │                                                        │
│           │             │                                                        │
│           └──────┬──────┘                                                        │
│                  │                                                               │
│                  │ HTTP/REST (up to 20 req/sec)                                  │
│                  ▼                                                               │
│     ┌────────────────────────────────────────────┐                               │
│     │                                            │                               │
│     │         RDP MSA System                     │                               │
│     │                                            │                               │
│     │   Microservices-based data processing      │                               │
│     │   system with service discovery,           │                               │
│     │   load balancing, and resilience           │                               │
│     │                                            │                               │
│     └────────────────────────────────────────────┘                               │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘

Legend:
┌─────────┐
│ Person/ │  External entity interacting with the system
│ System  │
└─────────┘

┌─────────┐
│ Software│  The system being described
│ System  │
└─────────┘
```

## Level 2: Container Diagram

Shows the high-level technical building blocks.

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                          │
│                                 RDP MSA System                                           │
│                                                                                          │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                              Service Discovery Layer                                │ │
│  │                                                                                     │ │
│  │    ┌─────────────────────┐              ┌─────────────────────┐                     │ │
│  │    │                     │   Peer       │                     │                     │ │
│  │    │   Eureka Server 1   │◄────────────►│   Eureka Server 4   │                     │ │
│  │    │   (Server 1:8088)   │ Replication  │   (Server 4:8088)   │                     │ │
│  │    │                     │              │                     │                     │ │
│  │    └─────────────────────┘              └─────────────────────┘                     │ │
│  │                                                                                     │ │
│  └─────────────────────────────────────────────────────────────────────────────────────┘ │
│                                          │                                               │
│                            Register/Discover Services                                    │
│                                          ▼                                               │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                              Consumer Services                                      │ │
│  │                                                                                     │ │
│  │   ┌──────────────────────────────────┐   ┌──────────────────────────────────┐       │ │
│  │   │       Trade Receiver Service          │   │      EQ Trade Handler Service          │       │ │
│  │   │                                  │   │                                  │       │ │
│  │   │  ┌────────┐ ┌────────┐           │   │                                  │       │ │
│  │   │  │ equity │ │ forex  │           │   │     Single Instance              │       │ │
│  │   │  │ :8082  │ │ :8083  │           │   │     Server 2:8090                │       │ │
│  │   │  └────────┘ └────────┘           │   │                                  │       │ │
│  │   │  ┌────────┐ ┌────────┐           │   │  - Calls Reference Lookup Service         │       │ │
│  │   │  │irswap  │ │support │           │   │  - Calls Check Eligible Service     │       │ │
│  │   │  │ :8082  │ │ :8084  │           │   │  - Local-first Load Balancing    │       │ │
│  │   │  └────────┘ └────────┘           │   │  - Resilience4j Integration      │       │ │
│  │   │                                  │   │                                  │       │ │
│  │   │  - Calls Reference Lookup Service         │   └──────────────────────────────────┘       │ │
│  │   │  - Calls Check Eligible Service     │                                              │ │
│  │   │  - Local-first Load Balancing    │                                              │ │
│  │   │  - Resilience4j Integration      │                                              │ │
│  │   └──────────────────────────────────┘                                              │ │
│  │                                                                                     │ │
│  └─────────────────────────────────────────────────────────────────────────────────────┘ │
│                                          │                                               │
│                              REST API Calls (Local-First LB)                             │
│                                          ▼                                               │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                              Provider Services                                      │ │
│  │                                                                                     │ │
│  │   ┌──────────────────────────────────┐   ┌──────────────────────────────────┐       │ │
│  │   │        Reference Lookup Service           │   │      Check Eligible Service         │       │ │
│  │   │                                  │   │                                  │       │ │
│  │   │   Instance 1: Server 1:8081      │   │   Instance 1: Server 1:8092      │       │ │
│  │   │   Instance 2: Server 3:8081      │   │   Instance 2: Server 3:8092      │       │ │
│  │   │                                  │   │                                  │       │ │
│  │   │   - Provides reference data      │   │   - Eligibility checking         │       │ │
│  │   │   - Registers with Eureka        │   │   - Registers with Eureka        │       │ │
│  │   │   - Exposes health endpoints     │   │   - Exposes health endpoints     │       │ │
│  │   │                                  │   │                                  │       │ │
│  │   └──────────────────────────────────┘   └──────────────────────────────────┘       │ │
│  │                                                                                     │ │
│  └─────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                          │
└──────────────────────────────────────────────────────────────────────────────────────────┘

Legend:
┌─────────────┐
│  Container  │  A separately deployable unit (service/application)
└─────────────┘
```

## Level 3: Component Diagram - Trade Receiver Service

Shows internal components of the Trade Receiver Service.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                               Trade Receiver Service                                             │
│                                                                                             │
│  ┌────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                                 API Layer                                              │ │
│  │                                                                                        │ │
│  │   ┌─────────────────────┐                                                              │ │
│  │   │                     │                                                              │ │
│  │   │  TradeReceiverController│  REST endpoints for data ingestion                           │ │
│  │   │                     │  Handles incoming requests                                   │ │
│  │   └──────────┬──────────┘                                                              │ │
│  │              │                                                                         │ │
│  └──────────────┼─────────────────────────────────────────────────────────────────────────┘ │
│                 ▼                                                                           │
│  ┌────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                                Service Layer                                           │ │
│  │                                                                                        │ │
│  │   ┌─────────────────────┐                                                              │ │
│  │   │                     │                                                              │ │
│  │   │  TradeReceiverService   │  Business logic for data processing                          │ │
│  │   │                     │  Orchestrates calls to downstream services                   │ │
│  │   └──────────┬──────────┘                                                              │ │
│  │              │                                                                         │ │
│  └──────────────┼─────────────────────────────────────────────────────────────────────────┘ │
│                 ▼                                                                           │
│  ┌────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                              Client Layer                                              │ │
│  │                                                                                        │ │
│  │   ┌─────────────────────┐         ┌─────────────────────┐                              │ │
│  │   │                     │         │                     │                              │ │
│  │   │  ReferenceLookupClient      │         │ CheckEligibleClient   │  Feign Clients with          │ │
│  │   │  (Feign + LB)       │         │ (Feign + LB)        │  LoadBalancer integration    │ │
│  │   │                     │         │                     │                              │ │
│  │   └──────────┬──────────┘         └──────────┬──────────┘                              │ │
│  │              │                               │                                         │ │
│  └──────────────┼───────────────────────────────┼─────────────────────────────────────────┘ │
│                 ▼                               ▼                                           │
│  ┌────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                         Load Balancing & Resilience Layer                              │ │
│  │                                                                                        │ │
│  │   ┌─────────────────────┐   ┌─────────────────────┐   ┌─────────────────────┐          │ │
│  │   │                     │   │                     │   │                     │          │ │
│  │   │ LocalFirstLB        │   │ ServiceInstance     │   │ HealthMonitor       │          │ │
│  │   │ Configuration       │   │ Registry            │   │ (ExecutorService)   │          │ │
│  │   │                     │   │ (Thread-Safe)       │   │                     │          │ │
│  │   │ - Local preference  │   │                     │   │ - Background checks │          │ │
│  │   │ - Load-aware        │   │ - Instance health   │   │ - Configurable      │          │ │
│  │   │ - Failover logic    │   │ - Load metrics      │   │   interval (10s)    │          │ │
│  │   │                     │   │ - Concurrent access │   │ - Updates registry  │          │ │
│  │   └─────────────────────┘   └─────────────────────┘   └─────────────────────┘          │ │
│  │                                                                                        │ │
│  │   ┌─────────────────────┐   ┌─────────────────────┐                                    │ │
│  │   │                     │   │                     │                                    │ │
│  │   │ RetryConfig         │   │ CircuitBreaker      │   Resilience4j components          │ │
│  │   │                     │   │ Config              │                                    │ │
│  │   │ - Exponential       │   │                     │                                    │ │
│  │   │   backoff           │   │ - Failure threshold │                                    │ │
│  │   │ - Max attempts      │   │ - Wait duration     │                                    │ │
│  │   │ - Instance rotation │   │ - Half-open state   │                                    │ │
│  │   │                     │   │                     │                                    │ │
│  │   └─────────────────────┘   └─────────────────────┘                                    │ │
│  │                                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                             │
│  ┌────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                           Infrastructure Layer                                         │ │
│  │                                                                                        │ │
│  │   ┌─────────────────────┐   ┌─────────────────────┐                                    │ │
│  │   │                     │   │                     │                                    │ │
│  │   │ EurekaClient        │   │ ActuatorEndpoints   │                                    │ │
│  │   │ Configuration       │   │                     │                                    │ │
│  │   │                     │   │ - /health           │                                    │ │
│  │   │ - Service registry  │   │ - /info             │                                    │ │
│  │   │ - Instance discovery│   │ - /metrics          │                                    │ │
│  │   │                     │   │                     │                                    │ │
│  │   └─────────────────────┘   └─────────────────────┘                                    │ │
│  │                                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

Legend:
┌─────────────┐
│  Component  │  A logical grouping of related functionality
└─────────────┘
```

## Level 3: Component Diagram - Client Load Balancer

Detailed view of the Local-First Load Balancing mechanism.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                          Local-First Client Load Balancer                                   │
│                                                                                             │
│                                                                                             │
│  ┌────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                              Request Flow                                              │ │
│  │                                                                                        │ │
│  │                           ┌─────────────┐                                              │ │
│  │                           │  Incoming   │                                              │ │
│  │                           │   Request   │                                              │ │
│  │                           └──────┬──────┘                                              │ │
│  │                                  │                                                     │ │
│  │                                  ▼                                                     │ │
│  │                    ┌─────────────────────────┐                                         │ │
│  │                    │   LocalFirstLB          │                                         │ │
│  │                    │   ServiceInstanceList   │                                         │ │
│  │                    │   Chooser               │                                         │ │
│  │                    └───────────┬─────────────┘                                         │ │
│  │                                │                                                       │ │
│  │              ┌─────────────────┼─────────────────┐                                     │ │
│  │              │                 │                 │                                     │ │
│  │              ▼                 ▼                 ▼                                     │ │
│  │   ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐                          │ │
│  │   │ 1. Check Local  │ │ 2. Check Load   │ │ 3. Select       │                          │ │
│  │   │    Instance     │ │    Metrics      │ │    Instance     │                          │ │
│  │   │                 │ │                 │ │                 │                          │ │
│  │   │ Is local        │ │ Get load from   │ │ Return best     │                          │ │
│  │   │ available?      │ │ registry        │ │ candidate       │                          │ │
│  │   └────────┬────────┘ └────────┬────────┘ └────────┬────────┘                          │ │
│  │            │                   │                   │                                   │ │
│  │            ▼                   ▼                   ▼                                   │ │
│  │   ┌─────────────────────────────────────────────────────────┐                          │ │
│  │   │              ServiceInstanceRegistry                    │                          │ │
│  │   │                  (Thread-Safe)                          │                          │ │
│  │   │                                                         │                          │ │
│  │   │  ┌──────────────────────────────────────────────────┐   │                          │ │
│  │   │  │  ConcurrentHashMap<ServiceId, List<Instance>>    │   │                          │ │
│  │   │  │                                                  │   │                          │ │
│  │   │  │  Instance:                                       │   │                          │ │
│  │   │  │  - host: String                                  │   │                          │ │
│  │   │  │  - port: int                                     │   │                          │ │
│  │   │  │  - isLocal: boolean                              │   │                          │ │
│  │   │  │  - healthy: AtomicBoolean                        │   │                          │ │
│  │   │  │  - currentLoad: AtomicInteger                    │   │                          │ │
│  │   │  │  - lastHealthCheck: AtomicLong                   │   │                          │ │
│  │   │  │                                                  │   │                          │ │
│  │   │  └──────────────────────────────────────────────────┘   │                          │ │
│  │   │                                                         │                          │ │
│  │   └─────────────────────────────────────────────────────────┘                          │ │
│  │                                  ▲                                                     │ │
│  │                                  │ Updates                                             │ │
│  │                                  │                                                     │ │
│  │   ┌─────────────────────────────────────────────────────────┐                          │ │
│  │   │            HealthMonitorExecutor                        │                          │ │
│  │   │                                                         │                          │ │
│  │   │  ScheduledExecutorService (configurable interval)       │                          │ │
│  │   │                                                         │                          │ │
│  │   │  For each service (refdata, eligibility):               │                          │ │
│  │   │    1. GET /actuator/health for each instance            │                          │ │
│  │   │    2. Update instance health status                     │                          │ │
│  │   │    3. Update load metrics (if available)                │                          │ │
│  │   │                                                         │                          │ │
│  │   └─────────────────────────────────────────────────────────┘                          │ │
│  │                                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                             │
│                                                                                             │
│  ┌────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                          Instance Selection Algorithm                                  │ │
│  │                                                                                        │ │
│  │   ┌─────────────────────────────────────────────────────────────────────────────────┐  │ │
│  │   │                                                                                 │  │ │
│  │   │  1. Filter healthy instances from registry                                      │  │ │
│  │   │                       │                                                         │  │ │
│  │   │                       ▼                                                         │  │ │
│  │   │  2. Check if LOCAL instance exists and is healthy                               │  │ │
│  │   │                       │                                                         │  │ │
│  │   │           ┌───────────┴───────────┐                                             │  │ │
│  │   │           │                       │                                             │  │ │
│  │   │        YES│                       │NO                                           │  │ │
│  │   │           ▼                       ▼                                             │  │ │
│  │   │  3a. Return LOCAL        3b. Sort REMOTE instances                              │  │ │
│  │   │      instance                by load (ascending)                                │  │ │
│  │   │                                   │                                             │  │ │
│  │   │                                   ▼                                             │  │ │
│  │   │                          4. Return instance with                                │  │ │
│  │   │                             lowest load                                         │  │ │
│  │   │                                                                                 │  │ │
│  │   └─────────────────────────────────────────────────────────────────────────────────┘  │ │
│  │                                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Level 3: Component Diagram - Passive Health Pattern

Detailed view of the Passive Health components and their interactions.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                         Passive Health Pattern Components                                   │
│                                                                                             │
│  ┌────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                              Request Flow (with Passive Health)                        │ │
│  │                                                                                        │ │
│  │   ┌─────────────────┐                                                                  │ │
│  │   │  Feign Client   │                                                                  │ │
│  │   │  (REST Call)    │                                                                  │ │
│  │   └────────┬────────┘                                                                  │ │
│  │            │                                                                           │ │
│  │            ▼                                                                           │ │
│  │   ┌─────────────────────────────────────────────────────────────────────────────────┐  │ │
│  │   │                    Resilience4j Retry Layer                                     │  │ │
│  │   │                                                                                 │  │ │
│  │   │   ┌───────────────────────┐        ┌───────────────────────────────────────┐    │  │ │
│  │   │   │     RetryConfig       │        │  PassiveHealthRetryEventListener      │    │  │ │
│  │   │   │                       │        │                                       │    │  │ │
│  │   │   │  max-attempts: 3      │◄──────►│  onRetry():                           │    │  │ │
│  │   │   │  wait-duration: 0ms   │ events │    - Mark instance UNHEALTHY          │    │  │ │
│  │   │   │  exponential: false   │        │    - Add to tried instances           │    │  │ │
│  │   │   │                       │        │  onSuccess(): Clear context           │    │  │ │
│  │   │   └───────────────────────┘        │  onError(): Log exhausted retries     │    │  │ │
│  │   │                                    └───────────────────────────────────────┘    │  │ │
│  │   │                                                     │                           │  │ │
│  │   └─────────────────────────────────────────────────────┼───────────────────────────┘  │ │
│  │                                                         │                              │ │
│  │            ┌────────────────────────────────────────────┘                              │ │
│  │            │                                                                           │ │
│  │            ▼                                                                           │ │
│  │   ┌─────────────────────────────────────────────────────────────────────────────────┐  │ │
│  │   │                     PassiveHealthContext (ThreadLocal)                          │  │ │
│  │   │                                                                                 │  │ │
│  │   │   ┌───────────────────────────────────────────────────────────────────────┐     │  │ │
│  │   │   │  RequestContext (per thread)                                          │     │  │ │
│  │   │   │                                                                       │     │  │ │
│  │   │   │   triedInstances: Set<String>     ← Track failed instances            │     │  │ │
│  │   │   │   currentInstanceId: String       ← Current target for failure mark   │     │  │ │
│  │   │   │   currentServiceId: String        ← Service being called              │     │  │ │
│  │   │   │                                                                       │     │  │ │
│  │   │   │   + markTried(instanceId)         ← Called by retry listener          │     │  │ │
│  │   │   │   + hasTried(instanceId): bool    ← Used by load balancer             │     │  │ │
│  │   │   │   + setCurrentInstance(svc, id)   ← Set before each attempt           │     │  │ │
│  │   │   └───────────────────────────────────────────────────────────────────────┘     │  │ │
│  │   │                                                                                 │  │ │
│  │   └─────────────────────────────────────────────────────────────────────────────────┘  │ │
│  │                                                         │                              │ │
│  │            ┌────────────────────────────────────────────┘                              │ │
│  │            │                                                                           │ │
│  │            ▼                                                                           │ │
│  │   ┌─────────────────────────────────────────────────────────────────────────────────┐  │ │
│  │   │                     LocalFirstLoadBalancer                                      │  │ │
│  │   │                                                                                 │  │ │
│  │   │   choose(serviceId):                                                            │  │ │
│  │   │     1. Get PassiveHealthContext.current()                                       │  │ │
│  │   │     2. Get triedInstances set                                                   │  │ │
│  │   │     3. Filter: healthy AND NOT in triedInstances                                │  │ │
│  │   │     4. If local available → select local                                        │  │ │
│  │   │     5. Else → select remote by lowest load                                      │  │ │
│  │   │     6. Call context.setCurrentInstance() for passive health tracking            │  │ │
│  │   │                                                                                 │  │ │
│  │   └─────────────────────────────────────────────────────────────────────────────────┘  │ │
│  │                                                         │                              │ │
│  │            ┌────────────────────────────────────────────┘                              │ │
│  │            │                                                                           │ │
│  │            ▼                                                                           │ │
│  │   ┌─────────────────────────────────────────────────────────────────────────────────┐  │ │
│  │   │                     ServiceInstanceRegistry                                     │  │ │
│  │   │                                                                                 │  │ │
│  │   │   Thread-Safe Operations:                                                       │  │ │
│  │   │   + markUnhealthy(serviceId, instanceId)  ← Called by PassiveHealthListener     │  │ │
│  │   │   + markHealthy(serviceId, instanceId)    ← Called by HealthMonitorService      │  │ │
│  │   │   + getHealthyInstances(serviceId)        ← Used by LoadBalancer                │  │ │
│  │   │   + getLocalInstance(serviceId)           ← Local-first selection               │  │ │
│  │   │   + getRemoteInstancesByLoad(serviceId)   ← Load-based fallback                 │  │ │
│  │   │                                                                                 │  │ │
│  │   └─────────────────────────────────────────────────────────────────────────────────┘  │ │
│  │                                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                             │
│  ┌────────────────────────────────────────────────────────────────────────────────────────┐ │
│  │                     Health Detection Responsibilities                                  │ │
│  │                                                                                        │ │
│  │   ┌───────────────────────────────────┐   ┌───────────────────────────────────┐        │ │
│  │   │     FAILURE DETECTION             │   │     RECOVERY DETECTION            │        │ │
│  │   │     (Real-time)                   │   │     (Background)                  │        │ │
│  │   │                                   │   │                                   │        │ │
│  │   │   PassiveHealthRetryEventListener │   │   HealthMonitorService            │        │ │
│  │   │                                   │   │                                   │        │ │
│  │   │   • Triggered during requests     │   │   • Runs every 30 seconds         │        │ │
│  │   │   • Immediate detection (0ms)     │   │   • Only checks UNHEALTHY insts   │        │ │
│  │   │   • Marks instance UNHEALTHY      │   │   • Marks recovered → HEALTHY     │        │ │
│  │   │   • No stale cache problem        │   │   • Restores traffic to instance  │        │ │
│  │   │                                   │   │                                   │        │ │
│  │   └───────────────────────────────────┘   └───────────────────────────────────┘        │ │
│  │                                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Class Diagram - Core Components

```
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                            │
│                              Core Classes                                                  │
│                                                                                            │
│  ┌───────────────────────────────┐       ┌───────────────────────────────┐                 │
│  │     ServiceInstanceInfo       │       │    ServiceInstanceRegistry    │                 │
│  ├───────────────────────────────┤       ├───────────────────────────────┤                 │
│  │ - serviceId: String           │       │ - instances: ConcurrentMap    │                 │
│  │ - host: String                │  ◄──  │   <String, List<Instance>>    │                 │
│  │ - port: int                   │       ├───────────────────────────────┤                 │
│  │ - isLocal: boolean            │       │ + getInstances(serviceId)     │                 │
│  │ - healthy: AtomicBoolean      │       │ + markUnhealthy(svc, instId)  │ ◄── Passive     │
│  │ - currentLoad: AtomicInteger  │       │ + markHealthy(svc, instId)    │     Health      │
│  │ - lastCheck: AtomicLong       │       │ + getLocalInstance(serviceId) │                 │
│  ├───────────────────────────────┤       │ + getHealthyInstances(svcId)  │                 │
│  │ + isHealthy(): boolean        │       │ + getRemoteInstancesByLoad()  │                 │
│  │ + getLoad(): int              │       └───────────────────────────────┘                 │
│  │ + markHealthy(boolean)        │                      ▲                                  │
│  │ + incrementLoad()             │                      │                                  │
│  │ + decrementLoad()             │                      │ uses                             │
│  └───────────────────────────────┘                      │                                  │
│                                          ┌───────────────────────────────┐                 │
│                                          │   LocalFirstLoadBalancer      │                 │
│  ┌───────────────────────────────┐       ├───────────────────────────────┤                 │
│  │  PassiveHealthContext         │       │ - registry: Registry          │                 │
│  ├───────────────────────────────┤       │ - serviceId: String           │                 │
│  │ - context: ThreadLocal        │◄──────┤───────────────────────────────┤                 │
│  ├───────────────────────────────┤       │ + choose(request): Instance   │                 │
│  │ + current(): RequestContext   │       │ - skipTriedInstances()        │                 │
│  │ + clear()                     │       │ - setCurrentInstanceInContext │                 │
│  └───────────────────────────────┘       └───────────────────────────────┘                 │
│            │                                            ▲                                  │
│            │ uses                                       │ notifies                         │
│            ▼                                            │                                  │
│  ┌───────────────────────────────┐       ┌───────────────────────────────┐                 │
│  │     RequestContext            │       │ PassiveHealthRetryEvent       │                 │
│  ├───────────────────────────────┤       │ Listener                      │                 │
│  │ - triedInstances: Set         │       ├───────────────────────────────┤                 │
│  │ - currentInstanceId: String   │◄──────│ - retryRegistry: RetryRegistry│                 │
│  │ - currentServiceId: String    │       │ - instanceRegistry: Registry  │                 │
│  ├───────────────────────────────┤       ├───────────────────────────────┤                 │
│  │ + markTried(instanceId)       │       │ + onRetry(event)              │                 │
│  │ + hasTried(instanceId): bool  │       │ + onSuccess(event)            │                 │
│  │ + setCurrentInstance()        │       │ + onError(event)              │                 │
│  │ + getTriedInstances(): Set    │       │ - registerEventConsumers()    │                 │
│  └───────────────────────────────┘       └───────────────────────────────┘                 │
│                                                                                            │
│  ┌───────────────────────────────┐       ┌───────────────────────────────┐                 │
│  │    HealthMonitorService       │       │   <<interface>>               │                 │
│  ├───────────────────────────────┤       │   ReactorServiceInstance      │                 │
│  │ - executor: ScheduledExecutor │       │   LoadBalancer                │                 │
│  │ - registry: Registry          │       ├───────────────────────────────┤                 │
│  │ - checkInterval: 30s          │       │ + choose(request): Mono       │                 │
│  │ - httpClient: RestClient      │       └───────────────────────────────┘                 │
│  ├───────────────────────────────┤                      ▲                                  │
│  │ + start()                     │                      │ implements                       │
│  │ + stop()                      │       ┌───────────────────────────────┐                 │
│  │ - checkUnhealthyInstances()   │       │ LocalFirstLoadBalancer        │                 │
│  │ - markRecoveredHealthy()      │       │ (as shown above)              │                 │
│  └───────────────────────────────┘       └───────────────────────────────┘                 │
│                                                                                            │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Deployment Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                              Deployment View                                                │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │                              Docker Host                                              │  │
│  │                                                                                       │  │
│  │   ┌─────────────────────────┐        ┌─────────────────────────┐                      │  │
│  │   │    Server 1 Container   │        │    Server 2 Container   │                      │  │
│  │   │                         │        │                         │                      │  │
│  │   │  ┌─────────────────┐    │        │  ┌─────────────────┐    │                      │  │
│  │   │  │ eureka:8088     │    │        │  │ regulatory:8090 │    │                      │  │
│  │   │  └─────────────────┘    │        │  └─────────────────┘    │                      │  │
│  │   │  ┌─────────────────┐    │        │  ┌─────────────────┐    │                      │  │
│  │   │  │ ingestion:8082  │    │        │  │ ingestion:8083  │    │                      │  │
│  │   │  │ (equity)        │    │        │  │ (forex)         │    │                      │  │
│  │   │  └─────────────────┘    │        │  └─────────────────┘    │                      │  │
│  │   │  ┌─────────────────┐    │        │                         │                      │  │
│  │   │  │ refdata:8081    │    │        └─────────────────────────┘                      │  │
│  │   │  └─────────────────┘    │                                                         │  │
│  │   │  ┌─────────────────┐    │        ┌─────────────────────────┐                      │  │
│  │   │  │ eligibility:8092│    │        │    Server 3 Container   │                      │  │
│  │   │  └─────────────────┘    │        │                         │                      │  │
│  │   │                         │        │  ┌─────────────────┐    │                      │  │
│  │   └─────────────────────────┘        │  │ refdata:8081    │    │                      │  │
│  │                                      │  └─────────────────┘    │                      │  │
│  │                                      │  ┌─────────────────┐    │                      │  │
│  │   ┌─────────────────────────┐        │  │ ingestion:8082  │    │                      │  │
│  │   │    Server 4 Container   │        │  │ (irswap)        │    │                      │  │
│  │   │                         │        │  └─────────────────┘    │                      │  │
│  │   │  ┌─────────────────┐    │        │  ┌─────────────────┐    │                      │  │
│  │   │  │ eureka:8088     │    │        │  │ eligibility:8092│    │                      │  │
│  │   │  └─────────────────┘    │        │  └─────────────────┘    │                      │  │
│  │   │  ┌─────────────────┐    │        │                         │                      │  │
│  │   │  │ ingestion:8084  │    │        └─────────────────────────┘                      │  │
│  │   │  │ (support)       │    │                                                         │  │
│  │   │  └─────────────────┘    │                                                         │  │
│  │   │                         │                                                         │  │
│  │   └─────────────────────────┘                                                         │  │
│  │                                                                                       │  │
│  │                         Network: rdp-network (bridge)                                 │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```
