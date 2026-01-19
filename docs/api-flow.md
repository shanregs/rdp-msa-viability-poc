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

### 3. Failover Flow with Passive Health (Local Instance Failure)

The system uses **Passive Health** pattern for immediate failover (0ms retry delay).

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐     ┌─────────────┐
│TradeReceiver│     │ LocalFirstLB │     │ Local       │     │ Remote      │
│  Service    │     │ + Resilience │     │ RefLookup   │     │ RefLookup   │
└──────┬──────┘     └──────┬───────┘     └──────┬──────┘     └──────┬──────┘
       │                   │                    │                   │
       │  getRefLookup()   │                    │                   │
       │──────────────────►│                    │                   │
       │                   │                    │                   │
       │                   │ choose() → local   │                   │
       │                   │  (set current instance in context)     │
       │                   │───────────────────►│                   │
       │                   │                    │                   │
       │                   │    TIMEOUT/ERROR   │                   │
       │                   │◄───────────────────│                   │
       │                   │                    │                   │
       │                   │  ┌─────────────────────────────────┐   │
       │                   │  │ PASSIVE HEALTH (Resilience4j):  │   │
       │                   │  │                                 │   │
       │                   │  │ 1. Mark local instance UNHEALTHY│   │
       │                   │  │ 2. Add to tried instances set   │   │
       │                   │  │ 3. IMMEDIATE retry (0ms delay)  │   │
       │                   │  │ 4. Load balancer skips tried    │   │
       │                   │  └─────────────────────────────────┘   │
       │                   │                    │                   │
       │                   │ choose() → remote (skip local)         │
       │                   │──────────────────────────────────────► │
       │                   │                    │                   │
       │                   │    Response        │                   │
       │                   │◄────────────────────────────────────── │
       │                   │                    │                   │
       │  Response         │                    │                   │
       │◄──────────────────│  Total failover time: ~100ms          │
       │                   │  (vs 3+ seconds with traditional)      │
```

**Key Differences from Traditional Retry:**
| Aspect | Traditional | Passive Health |
|--------|-------------|----------------|
| Retry delay | 500ms → 1000ms → 2000ms | 0ms (immediate) |
| Failure detection | Background polling | Real-time during request |
| Instance selection | May retry same instance | Skips already-tried instances |
| Total failover time | 3+ seconds | ~100ms |

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

### 5. Background Health Monitoring Flow (Recovery Detection)

With the **Passive Health** pattern, background health monitoring focuses on **recovery detection only**:
- **Failure detection**: Handled by passive health (real-time, during requests)
- **Recovery detection**: Handled by background health monitor (30-second interval)

```
┌────────────────┐     ┌─────────────────┐     ┌─────────────┐     ┌─────────────┐
│ HealthMonitor  │     │ ServiceInstance │     │ RefLookup   │     │ Eligibility │
│ (ExecutorSvc)  │     │ Registry        │     │  Service    │     │ Service     │
└───────┬────────┘     └────────┬────────┘     └──────┬──────┘     └──────┬──────┘
        │                       │                     │                   │
        │  ┌─────────────────────────────────────────────────────────┐    │
        │  │ Scheduled Task (every 30 seconds - RECOVERY FOCUSED)    │    │
        │  │ Only checks instances marked UNHEALTHY by passive health│    │
        │  └─────────────────────────────────────────────────────────┘    │
        │                       │                     │                   │
        │  getUnhealthyInstances()                    │                   │
        │──────────────────────►│                     │                   │
        │                       │                     │                   │
        │  [refdata-server1:8081]  (marked unhealthy)│                   │
        │◄──────────────────────│                     │                   │
        │                       │                     │                   │
        │  GET /actuator/health │                     │                   │
        │────────────────────────────────────────────►│                   │
        │                       │                     │                   │
        │  { "status": "UP" }   │  (service recovered)│                   │
        │◄────────────────────────────────────────────│                   │
        │                       │                     │                   │
        │  markHealthy(refdata-server1)               │                   │
        │──────────────────────►│                     │                   │
        │                       │                     │                   │
        │                       │  ┌─────────────────────────────────┐    │
        │                       │  │ RECOVERY: Instance marked HEALTHY│    │
        │                       │  │ Traffic will resume to instance │    │
        │                       │  └─────────────────────────────────┘    │
        │                       │                     │                   │
        │  ┌─────────────────────────────────────────────────────────┐    │
        │  │ Wait for next scheduled interval (30 seconds)           │    │
        │  └─────────────────────────────────────────────────────────┘    │
        │                       │                     │                   │
```

**Health Monitoring Responsibilities:**
| Component | Responsibility |
|-----------|----------------|
| **Passive Health** | Marks instances UNHEALTHY during request failures (real-time) |
| **Background Monitor** | Detects instance RECOVERY by checking unhealthy instances (30s) |

## Client Load Balancer Algorithm

### Instance Selection Logic (with Passive Health)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                                                                              │
│            Local-First Load Balancer Algorithm (Passive Health)              │
│                                                                              │
│  Input: serviceId (e.g., "reference-lookup-service", "check-eligible-service")│
│  Output: ServiceInstance to route the request to                             │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                                                                         │ │
│  │  1. GET PassiveHealthContext for this request thread                    │ │
│  │     context = PassiveHealthContext.current()                            │ │
│  │     triedInstances = context.getTriedInstances()                        │ │
│  │                                                                         │ │
│  │  2. GET all instances from ServiceInstanceRegistry                      │ │
│  │     instances = registry.getInstances(serviceId)                        │ │
│  │                                                                         │ │
│  │  3. FILTER healthy instances AND not already tried                      │ │
│  │     availableInstances = instances.filter(i ->                          │ │
│  │         i.isHealthy() && !triedInstances.contains(i.getId()))           │ │
│  │                                                                         │ │
│  │  4. IF no available instances                                           │ │
│  │     THROW ServiceUnavailableException                                   │ │
│  │                                                                         │ │
│  │  5. FIND local instance (not already tried)                             │ │
│  │     localInstance = availableInstances.find(i -> i.isLocal())           │ │
│  │                                                                         │ │
│  │  6. IF localInstance exists AND localInstance.isHealthy()               │ │
│  │     context.setCurrentInstance(serviceId, localInstance.getId())        │ │
│  │     RETURN localInstance                                                │ │
│  │                                                                         │ │
│  │  7. ELSE (no local or local unhealthy/tried)                            │ │
│  │     remoteInstances = availableInstances.filter(i -> !i.isLocal())      │ │
│  │     sortedByLoad = remoteInstances.sortBy(i -> i.getLoad())             │ │
│  │     selected = sortedByLoad.first()  // Lowest load                     │ │
│  │     context.setCurrentInstance(serviceId, selected.getId())             │ │
│  │     RETURN selected                                                     │ │
│  │                                                                         │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  PASSIVE HEALTH INTEGRATION:                                                 │
│  - PassiveHealthContext tracks tried instances during retry cycle            │
│  - setCurrentInstance() allows PassiveHealthRetryEventListener to know       │
│    which instance failed when marking it unhealthy                           │
│  - triedInstances set prevents retrying the same failed instance             │
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

### Retry Configuration (Passive Health)

The retry configuration uses **0ms wait-duration** for immediate failover with passive health:

```yaml
resilience4j:
  retry:
    instances:
      referenceLookup:
        max-attempts: 3
        wait-duration: 0ms                    # Immediate retry for passive health
        enable-exponential-backoff: false     # No backoff delay
        retry-exceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
          - feign.FeignException
        ignore-exceptions:
          - com.bmo.rdp.common.exception.BusinessException

      checkEligible:
        max-attempts: 3
        wait-duration: 0ms                    # Immediate retry for passive health
        enable-exponential-backoff: false     # No backoff delay
```

**Why 0ms wait-duration?**
- Passive health marks failed instances unhealthy immediately
- Load balancer skips unhealthy/tried instances on retry
- No need to wait - the next attempt goes to a different, healthy instance
- Result: ~100ms total failover time vs 3+ seconds with traditional retry

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
