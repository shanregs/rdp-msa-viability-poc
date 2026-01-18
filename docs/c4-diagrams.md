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
│  │ - healthy: AtomicBoolean      │       │ + updateHealth(instance, bool)│                 │
│  │ - currentLoad: AtomicInteger  │       │ + updateLoad(instance, int)   │                 │
│  │ - lastCheck: AtomicLong       │       │ + getLocalInstance(serviceId) │                 │
│  ├───────────────────────────────┤       │ + getHealthyInstances(svcId)  │                 │
│  │ + isHealthy(): boolean        │       └───────────────────────────────┘                 │
│  │ + getLoad(): int              │                      ▲                                  │
│  │ + markHealthy(boolean)        │                      │                                  │
│  │ + incrementLoad()             │                      │ uses                             │
│  │ + decrementLoad()             │                      │                                  │
│  └───────────────────────────────┘       ┌───────────────────────────────┐                 │
│                                          │   LocalFirstLoadBalancer      │                 │
│                                          ├───────────────────────────────┤                 │
│                                          │ - registry: Registry          │                 │
│                                          │ - localHost: String           │                 │
│  ┌───────────────────────────────┐       ├───────────────────────────────┤                 │
│  │    HealthMonitorService       │       │ + choose(serviceId): Instance │                 │
│  ├───────────────────────────────┤       │ - selectLocalFirst(): Instance│                 │
│  │ - executor: ScheduledExecutor │──────►│ - selectByLoad(): Instance    │                 │
│  │ - registry: Registry          │       └───────────────────────────────┘                 │
│  │ - checkInterval: Duration     │                                                         │
│  │ - httpClient: RestClient      │                                                         │
│  ├───────────────────────────────┤                                                         │
│  │ + start()                     │       ┌───────────────────────────────┐                 │
│  │ + stop()                      │       │   <<interface>>               │                 │
│  │ - checkHealth(instance)       │       │   ReactorServiceInstance      │                 │
│  │ - updateRegistry(results)     │       │   ListSupplier                │                 │
│  └───────────────────────────────┘       ├───────────────────────────────┤                 │
│                                          │ + get(): Flux<List<Instance>> │                 │
│                                          └───────────────────────────────┘                 │
│                                                         ▲                                  │
│  ┌───────────────────────────────┐                      │ implements                       │
│  │    ResilienceConfig           │       ┌───────────────────────────────┐                 │
│  ├───────────────────────────────┤       │ LocalFirstServiceInstance     │                 │
│  │ - maxRetries: int             │       │ ListSupplier                  │                 │
│  │ - waitDuration: Duration      │       ├───────────────────────────────┤                 │
│  │ - multiplier: double          │       │ - loadBalancer: LocalFirstLB  │                 │
│  │ - circuitBreakerThreshold: %  │       │ - serviceId: String           │                 │
│  ├───────────────────────────────┤       ├───────────────────────────────┤                 │
│  │ + retryConfig(): RetryConfig  │       │ + get(): Flux<List<Instance>> │                 │
│  │ + circuitBreaker(): CB        │       └───────────────────────────────┘                 │
│  └───────────────────────────────┘                                                         │
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
