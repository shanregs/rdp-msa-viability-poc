# API Flow Documentation

This document describes the API call flows, sequence diagrams, and client-side load balancing behavior.

## Service Dependencies

```
┌─────────────────┐          ┌─────────────────┐
│                 │          │                 │
│   Ingestion     │─────────►│   RefLookup     │
│   Service       │          │    Service      │
│                 │─────┐    │                 │
└─────────────────┘     │    └─────────────────┘
                        │
                        │    ┌─────────────────┐
                        │    │                 │
                        └───►│  CheckEligible  │
                             │    Service      │
                             └─────────────────┘

┌─────────────────┐          ┌─────────────────┐
│                 │          │                 │
│   Regulatory    │─────────►│   RefLookup     │
│   Service       │          │    Service      │
│                 │─────┐    │                 │
└─────────────────┘     │    └─────────────────┘
                        │
                        │    ┌─────────────────┐
                        │    │                 │
                        └───►│  CheckEligible  │
                             │    Service      │
                             └─────────────────┘
```

## Sequence Diagrams

### 1. Basic Request Flow (Happy Path)

```
┌────────┐     ┌─────────────┐     ┌───────────────┐     ┌─────────────┐     ┌─────────────┐
│ Client │     │TradeReceiver│     │ LocalFirstLB  │     │ RefLookup   │     │ Eligibility │
└───┬────┘     └──────┬──────┘     └───────┬───────┘     └──────┬──────┘     └──────┬──────┘
    │                 │                    │                    │                   │
    │  POST /ingest   │                    │                    │                   │
    │────────────────►│                    │                    │                   │
    │                 │                    │                    │                   │
    │                 │  getRefLookup()      │                    │                   │
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
    │                 │                    │    RefLookup         │                   │
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
│TradeReceiver│     │ LocalFirstLB │     │ ServiceInstance │     │  Target Service │
│  Service    │     │              │     │ Registry        │     │  (RefLookup/Elig) │
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
│TradeReceiver│     │ LocalFirstLB │     │ Local       │     │ Remote      │
│  Service    │     │ + Resilience │     │ RefLookup     │     │ RefLookup     │
└──────┬──────┘     └──────┬───────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                    │                   │
       │  getRefLookup()     │                    │                   │
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
│TradeReceiver│     │ CircuitBreaker│    │ LocalFirstLB   │     │ RefLookup   │
│  Service    │     │              │     │                │     │  Service    │
└──────┬──────┘     └──────┬───────┘     └───────┬────────┘     └──────┬──────┘
       │                   │                     │                     │
       │  getRefLookup()     │                     │                     │
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
       │  getRefLookup()     │                     │                     │
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
       │  getRefLookup()     │                     │                     │
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
│ HealthMonitor  │     │ ServiceInstance │     │ RefLookup   │     │ Eligibility │
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
│  Input: serviceId (e.g., "reference-lookup-service", "check-eligible-service")           │
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
      reference-lookup-service:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
        ignore-exceptions:
          - com.bmo.rdp.common.exception.BusinessException

      check-eligible-service:
        max-attempts: 3
        wait-duration: 500ms
        exponential-backoff-multiplier: 2
```

### Circuit Breaker Configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      reference-lookup-service:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        automatic-transition-from-open-to-half-open-enabled: true

      check-eligible-service:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
```

## API Endpoints

### Trade Receiver Service

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/ingest` | Ingest data |
| GET | `/api/v1/ingest/{id}` | Get ingestion status |
| GET | `/actuator/health` | Health check |

### EQ Trade Handler Service

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/regulatory/process` | Process regulatory data |
| GET | `/api/v1/regulatory/{id}` | Get processing status |
| GET | `/actuator/health` | Health check |

### Reference Lookup Service

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/refdata/{type}` | Get reference data by type |
| GET | `/api/v1/refdata/{type}/{id}` | Get specific reference data |
| GET | `/actuator/health` | Health check |

### Check Eligible Service

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/eligibility/check` | Check eligibility |
| GET | `/api/v1/eligibility/{id}` | Get eligibility result |
| GET | `/actuator/health` | Health check |
