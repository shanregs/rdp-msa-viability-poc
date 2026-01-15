# API Flow Documentation

This document describes the API call flows, sequence diagrams, and client-side load balancing behavior.

## Service Dependencies

```
┌─────────────────┐          ┌─────────────────┐
│                 │          │                 │
│   Ingestion     │─────────►│    RefData      │
│   Service       │          │    Service      │
│                 │─────┐    │                 │
└─────────────────┘     │    └─────────────────┘
                        │
                        │    ┌─────────────────┐
                        │    │                 │
                        └───►│   Eligibility   │
                             │    Service      │
                             └─────────────────┘

┌─────────────────┐          ┌─────────────────┐
│                 │          │                 │
│   Regulatory    │─────────►│    RefData      │
│   Service       │          │    Service      │
│                 │─────┐    │                 │
└─────────────────┘     │    └─────────────────┘
                        │
                        │    ┌─────────────────┐
                        │    │                 │
                        └───►│   Eligibility   │
                             │    Service      │
                             └─────────────────┘
```

## Sequence Diagrams

### 1. Basic Request Flow (Happy Path)

```
┌────────┐     ┌─────────────┐     ┌───────────────┐     ┌─────────────┐     ┌─────────────┐
│ Client │     │  Ingestion  │     │ LocalFirstLB  │     │  RefData    │     │ Eligibility │
└───┬────┘     └──────┬──────┘     └───────┬───────┘     └──────┬──────┘     └──────┬──────┘
    │                 │                    │                    │                   │
    │  POST /ingest   │                    │                    │                   │
    │────────────────►│                    │                    │                   │
    │                 │                    │                    │                   │
    │                 │  getRefData()      │                    │                   │
    │                 │───────────────────►│                    │                   │
    │                 │                    │                    │                   │
    │                 │                    │  choose(refdata)   │                   │
    │                 │                    │────┐               │                   │
    │                 │                    │    │ Check local   │                   │
    │                 │                    │◄───┘ instance      │                   │
    │                 │                    │                    │                   │
    │                 │                    │  Local instance    │                   │
    │                 │                    │  available         │                   │
    │                 │                    │───────────────────►│                   │
    │                 │                    │                    │                   │
    │                 │                    │    RefData         │                   │
    │                 │◄───────────────────│◄───────────────────│                   │
    │                 │                    │                    │                   │
    │                 │  checkEligibility()│                    │                   │
    │                 │───────────────────►│                    │                   │
    │                 │                    │                    │                   │
    │                 │                    │ choose(eligibility)│                   │
    │                 │                    │────┐               │                   │
    │                 │                    │    │ Check local   │                   │
    │                 │                    │◄───┘ instance      │                   │
    │                 │                    │                    │                   │
    │                 │                    │  Local instance    │                   │
    │                 │                    │  available         │                   │
    │                 │                    │──────────────────────────────────────► │
    │                 │                    │                    │                   │
    │                 │                    │    Eligibility     │                   │
    │                 │◄───────────────────│◄────────────────────────────────────── │
    │                 │                    │                    │                   │
    │  Response       │                    │                    │                   │
    │◄────────────────│                    │                    │                   │
    │                 │                    │                    │                   │
```

### 2. Local-First Load Balancing Flow

```
┌─────────────┐     ┌──────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Ingestion  │     │ LocalFirstLB │     │ ServiceInstance │     │  Target Service │
│  Service    │     │              │     │ Registry        │     │  (RefData/Elig) │
└──────┬──────┘     └──────┬───────┘     └────────┬────────┘     └────────┬────────┘
       │                   │                      │                       │
       │  choose(serviceId)│                      │                       │
       │──────────────────►│                      │                       │
       │                   │                      │                       │
       │                   │  getHealthyInstances │                       │
       │                   │─────────────────────►│                       │
       │                   │                      │                       │
       │                   │  List<Instance>      │                       │
       │                   │◄─────────────────────│                       │
       │                   │                      │                       │
       │                   │  ┌─────────────────────────────────────┐     │
       │                   │  │ Instance Selection Algorithm:       │     │
       │                   │  │                                     │     │
       │                   │  │ 1. Filter: healthy instances only   │     │
       │                   │  │ 2. Find: local instance (isLocal)   │     │
       │                   │  │ 3. If local healthy: return local   │     │
       │                   │  │ 4. Else: sort by load, return min   │     │
       │                   │  └─────────────────────────────────────┘     │
       │                   │                      │                       │
       │  ServiceInstance  │                      │                       │
       │◄──────────────────│                      │                       │
       │                   │                      │                       │
       │  HTTP Request     │                      │                       │
       │──────────────────────────────────────────────────────────────────►
       │                   │                      │                       │
       │  Response         │                      │                       │
       │◄──────────────────────────────────────────────────────────────────
       │                   │                      │                       │
```

### 3. Failover Flow (Local Instance Failure)

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐     ┌─────────────┐
│  Ingestion  │     │ LocalFirstLB │     │ Local       │     │ Remote      │
│  Service    │     │ + Resilience │     │ RefData     │     │ RefData     │
└──────┬──────┘     └──────┬───────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                    │                   │
       │  getRefData()     │                    │                   │
       │──────────────────►│                    │                   │
       │                   │                    │                   │
       │                   │ choose() → local   │                   │
       │                   │───────────────────►│                   │
       │                   │                    │                   │
       │                   │    TIMEOUT/ERROR   │                   │
       │                   │◄───────────────────│                   │
       │                   │                    │                   │
       │                   │  ┌─────────────────────────────────┐   │
       │                   │  │ Retry Logic (Resilience4j):     │   │
       │                   │  │                                 │   │
       │                   │  │ 1. Mark local as unhealthy      │   │
       │                   │  │ 2. Select next instance (remote)│   │
       │                   │  │ 3. Apply exponential backoff    │   │
       │                   │  └─────────────────────────────────┘   │
       │                   │                    │                   │
       │                   │ choose() → remote  │                   │
       │                   │──────────────────────────────────────► │
       │                   │                    │                   │
       │                   │    Response        │                   │
       │                   │◄────────────────────────────────────── │
       │                   │                    │                   │
       │  Response         │                    │                   │
       │◄──────────────────│                    │                   │
       │                   │                    │                   │
```

### 4. Circuit Breaker Flow

```
┌─────────────┐     ┌──────────────┐     ┌────────────────┐     ┌─────────────┐
│  Ingestion  │     │ CircuitBreaker│    │ LocalFirstLB   │     │  RefData    │
│  Service    │     │              │     │                │     │  Service    │
└──────┬──────┘     └──────┬───────┘     └───────┬────────┘     └──────┬──────┘
       │                   │                     │                     │
       │  getRefData()     │                     │                     │
       │──────────────────►│                     │                     │
       │                   │                     │                     │
       │                   │  State: CLOSED      │                     │
       │                   │─────────────────────►                     │
       │                   │                     │────────────────────►│
       │                   │                     │      ERROR          │
       │                   │◄────────────────────│◄────────────────────│
       │                   │  failures++         │                     │
       │                   │                     │                     │
       │                   │  ... repeated failures ...                │
       │                   │                     │                     │
       │                   │  ┌─────────────────────────────────────┐  │
       │                   │  │ Failure threshold reached           │  │
       │                   │  │ State: CLOSED → OPEN                │  │
       │                   │  └─────────────────────────────────────┘  │
       │                   │                     │                     │
       │  getRefData()     │                     │                     │
       │──────────────────►│                     │                     │
       │                   │                     │                     │
       │                   │  State: OPEN        │                     │
       │                   │  ┌─────────────────────────────────────┐  │
       │                   │  │ Immediately reject request          │  │
       │                   │  │ Return fallback or error            │  │
       │                   │  └─────────────────────────────────────┘  │
       │                   │                     │                     │
       │  Fallback/Error   │                     │                     │
       │◄──────────────────│                     │                     │
       │                   │                     │                     │
       │                   │  ... wait duration passes ...             │
       │                   │                     │                     │
       │                   │  ┌─────────────────────────────────────┐  │
       │                   │  │ State: OPEN → HALF_OPEN             │  │
       │                   │  │ Allow limited requests              │  │
       │                   │  └─────────────────────────────────────┘  │
       │                   │                     │                     │
       │  getRefData()     │                     │                     │
       │──────────────────►│                     │                     │
       │                   │                     │                     │
       │                   │  State: HALF_OPEN   │                     │
       │                   │─────────────────────►                     │
       │                   │                     │────────────────────►│
       │                   │                     │      SUCCESS        │
       │                   │◄────────────────────│◄────────────────────│
       │                   │                     │                     │
       │                   │  ┌─────────────────────────────────────┐  │
       │                   │  │ Success threshold reached           │  │
       │                   │  │ State: HALF_OPEN → CLOSED           │  │
       │                   │  └─────────────────────────────────────┘  │
       │                   │                     │                     │
       │  Response         │                     │                     │
       │◄──────────────────│                     │                     │
       │                   │                     │                     │
```

### 5. Background Health Monitoring Flow

```
┌────────────────┐     ┌─────────────────┐     ┌─────────────┐     ┌─────────────┐
│ HealthMonitor  │     │ ServiceInstance │     │  RefData    │     │ Eligibility │
│ (ExecutorSvc)  │     │ Registry        │     │  Service    │     │ Service     │
└───────┬────────┘     └────────┬────────┘     └──────┬──────┘     └──────┬──────┘
        │                       │                     │                   │
        │  ┌─────────────────────────────────────────────────────────┐    │
        │  │ Scheduled Task (every 10 seconds - configurable)        │    │
        │  └─────────────────────────────────────────────────────────┘    │
        │                       │                     │                   │
        │  GET /actuator/health │                     │                   │
        │────────────────────────────────────────────►│                   │
        │                       │                     │                   │
        │  { "status": "UP" }   │                     │                   │
        │◄────────────────────────────────────────────│                   │
        │                       │                     │                   │
        │  GET /actuator/health │                     │                   │
        │────────────────────────────────────────────────────────────────►
        │                       │                     │                   │
        │  { "status": "UP" }   │                     │                   │
        │◄────────────────────────────────────────────────────────────────
        │                       │                     │                   │
        │  updateHealth(refdata-1, UP)                │                   │
        │──────────────────────►│                     │                   │
        │                       │                     │                   │
        │  updateHealth(eligibility-1, UP)            │                   │
        │──────────────────────►│                     │                   │
        │                       │                     │                   │
        │                       │  ┌─────────────────────────────────┐    │
        │                       │  │ Thread-safe update:             │    │
        │                       │  │ ConcurrentHashMap.compute()     │    │
        │                       │  │ AtomicBoolean.set()             │    │
        │                       │  └─────────────────────────────────┘    │
        │                       │                     │                   │
        │  ┌─────────────────────────────────────────────────────────┐    │
        │  │ Wait for next scheduled interval (10 seconds)           │    │
        │  └─────────────────────────────────────────────────────────┘    │
        │                       │                     │                   │
```

## Client Load Balancer Algorithm

### Instance Selection Logic

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                                                                              │
│                    Local-First Load Balancer Algorithm                       │
│                                                                              │
│  Input: serviceId (e.g., "refdata-service", "eligibility-service")           │
│  Output: ServiceInstance to route the request to                             │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                                                                         │ │
│  │  1. GET all instances from ServiceInstanceRegistry                      │ │
│  │     instances = registry.getInstances(serviceId)                        │ │
│  │                                                                         │ │
│  │  2. FILTER healthy instances                                            │ │
│  │     healthyInstances = instances.filter(i -> i.isHealthy())             │ │
│  │                                                                         │ │
│  │  3. IF no healthy instances                                             │ │
│  │     THROW ServiceUnavailableException                                   │ │
│  │                                                                         │ │
│  │  4. FIND local instance                                                 │ │
│  │     localInstance = healthyInstances.find(i -> i.isLocal())             │ │
│  │                                                                         │ │
│  │  5. IF localInstance exists AND localInstance.isHealthy()               │ │
│  │     RETURN localInstance                                                │ │
│  │                                                                         │ │
│  │  6. ELSE (no local or local unhealthy)                                  │ │
│  │     remoteInstances = healthyInstances.filter(i -> !i.isLocal())        │ │
│  │     sortedByLoad = remoteInstances.sortBy(i -> i.getLoad())             │ │
│  │     RETURN sortedByLoad.first()  // Lowest load                         │ │
│  │                                                                         │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Load Calculation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│                         Load Tracking Mechanism                             │
│                                                                             │
│  Option 1: Active Request Count                                             │
│  ─────────────────────────────────                                          │
│  - Increment on request start                                               │
│  - Decrement on request complete                                            │
│  - AtomicInteger for thread-safety                                          │
│                                                                             │
│  Option 2: Health Endpoint Metrics (Recommended)                            │
│  ───────────────────────────────────────────────                            │
│  - Query /actuator/metrics/http.server.requests                             │
│  - Background thread updates load periodically                              │
│  - Less overhead on actual requests                                         │
│                                                                             │
│  Option 3: Response Time Based                                              │
│  ─────────────────────────────────                                          │
│  - Track average response time                                              │
│  - Higher response time = higher load                                       │
│  - Exponential moving average                                               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Resilience Configuration

### Retry Configuration

```yaml
resilience4j:
  retry:
    instances:
      refdata-service:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
        ignore-exceptions:
          - com.bmo.rdp.common.exception.BusinessException

      eligibility-service:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
```

### Circuit Breaker Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      refdata-service:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true

      eligibility-service:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
```

## API Endpoints

### Ingestion Service

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ingest` | Ingest data |
| GET | `/api/v1/ingest/{id}` | Get ingestion status |
| GET | `/actuator/health` | Health check |

### Regulatory Service

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/regulatory/process` | Process regulatory data |
| GET | `/api/v1/regulatory/{id}` | Get processing status |
| GET | `/actuator/health` | Health check |

### RefData Service

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/refdata/{type}` | Get reference data by type |
| GET | `/api/v1/refdata/{type}/{id}` | Get specific reference data |
| GET | `/actuator/health` | Health check |

### Eligibility Service

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/eligibility/check` | Check eligibility |
| GET | `/api/v1/eligibility/{id}` | Get eligibility result |
| GET | `/actuator/health` | Health check |
