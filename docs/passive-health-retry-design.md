# Passive Health with Immediate Retry Design

## Overview

This document describes the **Passive Health** pattern for handling service failures with immediate retry and failover in the RDP-MSA microservices architecture.

### Problem Statement

Traditional health monitoring relies on **background polling** (e.g., every 10 seconds), which creates:
- **Latency gap**: Requests fail while waiting for health check to detect failure
- **Stale cache**: Remote instances might be dead but cached as "healthy"
- **Poor user experience**: Unnecessary failures that could be avoided with immediate failover

### Solution: Passive Health

**Passive Health** uses **actual request failures as real-time health signals** instead of relying solely on background polling.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│   ACTIVE HEALTH (Traditional)     vs      PASSIVE HEALTH (Recommended)     │
│   ─────────────────────────────           ───────────────────────────────  │
│                                                                             │
│   Poll every 10s                          Use failures as health signals   │
│   Wait for next poll on failure           Mark unhealthy immediately       │
│   Stale data between polls                Real-time health updates         │
│   Background thread overhead              No extra threads for detection   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         PASSIVE HEALTH ARCHITECTURE                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        TRADE RECEIVER SERVICE                        │   │
│  │                           (Consumer)                                 │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌────────────┐  │   │
│  │  │   Feign     │  │  Resilient  │  │  Local-First│  │  Service   │  │   │
│  │  │   Client    │──│  Retry      │──│  Load       │──│  Instance  │  │   │
│  │  │             │  │  Handler    │  │  Balancer   │  │  Registry  │  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  └────────────┘  │   │
│  │                          │                               ▲          │   │
│  │                          │ On Failure:                   │          │   │
│  │                          │ Mark Unhealthy               │          │   │
│  │                          └───────────────────────────────┘          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                      │                                      │
│                                      ▼                                      │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │                     SERVICE INSTANCES                                 │  │
│  │  ┌────────────────┐    ┌────────────────┐    ┌────────────────┐      │  │
│  │  │ LOCAL INSTANCE │    │ REMOTE INST 1  │    │ REMOTE INST 2  │      │  │
│  │  │   (Server 1)   │    │   (Server 3)   │    │   (Server N)   │      │  │
│  │  │                │    │                │    │                │      │  │
│  │  │  Priority: 1   │    │  Priority: 2   │    │  Priority: 3   │      │  │
│  │  └────────────────┘    └────────────────┘    └────────────────┘      │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Service Instance Registry (Enhanced)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     SERVICE INSTANCE REGISTRY                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │  Service: reference-lookup-service                                     │ │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │ │
│  │  │ Instance ID                │ Host    │ Port │ Local │ Health   │  │ │
│  │  ├────────────────────────────┼─────────┼──────┼───────┼──────────┤  │ │
│  │  │ ref-lookup:server1:8081    │ server1 │ 8081 │ true  │ HEALTHY  │  │ │
│  │  │ ref-lookup:server3:8081    │ server3 │ 8081 │ false │ HEALTHY  │  │ │
│  │  └─────────────────────────────────────────────────────────────────┘  │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                             │
│  Health Status Updates:                                                     │
│  ─────────────────────                                                      │
│  1. PASSIVE: Updated immediately when request fails (real-time)            │
│  2. ACTIVE:  Updated by background health monitor (every 30s for recovery) │
│                                                                             │
│  Health State Machine:                                                      │
│  ────────────────────                                                       │
│                                                                             │
│       Request Success          Request Failure                              │
│            │                        │                                       │
│            ▼                        ▼                                       │
│      ┌──────────┐             ┌───────────┐                                │
│      │ HEALTHY  │◄────────────│ UNHEALTHY │                                │
│      └──────────┘  Background └───────────┘                                │
│                    Health Check                                             │
│                    Succeeds                                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Sequence Diagrams

### Scenario 1: Happy Path (Local Instance Healthy)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    HAPPY PATH - LOCAL INSTANCE HEALTHY                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Client          TradeReceiver       LoadBalancer       Registry       Local│
│    │                  │                   │               │              │  │
│    │  POST /trade     │                   │               │              │  │
│    │─────────────────►│                   │               │              │  │
│    │                  │                   │               │              │  │
│    │                  │  getHealthyInst() │               │              │  │
│    │                  │──────────────────►│               │              │  │
│    │                  │                   │  getLocal()   │              │  │
│    │                  │                   │──────────────►│              │  │
│    │                  │                   │  Instance A   │              │  │
│    │                  │                   │◄──────────────│              │  │
│    │                  │                   │               │              │  │
│    │                  │                   │  isHealthy?   │              │  │
│    │                  │                   │──────────────►│              │  │
│    │                  │                   │    true       │              │  │
│    │                  │                   │◄──────────────│              │  │
│    │                  │   Instance A      │               │              │  │
│    │                  │◄──────────────────│               │              │  │
│    │                  │                   │               │              │  │
│    │                  │                   GET /refdata    │              │  │
│    │                  │──────────────────────────────────────────────────►│  │
│    │                  │                   │               │              │  │
│    │                  │                   │   200 OK      │              │  │
│    │                  │◄──────────────────────────────────────────────────│  │
│    │                  │                   │               │              │  │
│    │   200 OK         │                   │               │              │  │
│    │◄─────────────────│                   │               │              │  │
│    │                  │                   │               │              │  │
│                                                                             │
│  Result: Request served by LOCAL instance (lowest latency)                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Scenario 2: Local Fails → Immediate Failover to Remote

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              LOCAL FAILS - PASSIVE HEALTH + IMMEDIATE FAILOVER              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Client      TradeReceiver    LoadBalancer    Registry    Local    Remote  │
│    │              │               │              │          │         │    │
│    │ POST /trade  │               │              │          │         │    │
│    │─────────────►│               │              │          │         │    │
│    │              │               │              │          │         │    │
│    │              │ getInstances()│              │          │         │    │
│    │              │──────────────►│              │          │         │    │
│    │              │               │ getLocal()   │          │         │    │
│    │              │               │─────────────►│          │         │    │
│    │              │               │ Instance A   │          │         │    │
│    │              │               │◄─────────────│          │         │    │
│    │              │ [Local A]     │              │          │         │    │
│    │              │◄──────────────│              │          │         │    │
│    │              │               │              │          │         │    │
│    │              │           GET /refdata       │          │         │    │
│    │              │─────────────────────────────────────────►│         │    │
│    │              │               │              │          │         │    │
│    │              │           Connection Refused │          │         │    │
│    │              │◄─────────────────────────────────────────│         │    │
│    │              │               │              │          │         │    │
│    │              │               │              │          │         │    │
│    │              │  ┌────────────────────────────────────┐ │         │    │
│    │              │  │ PASSIVE HEALTH UPDATE              │ │         │    │
│    │              │  │ Mark Instance A as UNHEALTHY       │ │         │    │
│    │              │  │ (Immediate - no waiting!)          │ │         │    │
│    │              │  └────────────────────────────────────┘ │         │    │
│    │              │               │              │          │         │    │
│    │              │ markUnhealthy(A)             │          │         │    │
│    │              │─────────────────────────────►│          │         │    │
│    │              │               │              │          │         │    │
│    │              │               │              │          │         │    │
│    │              │  ┌────────────────────────────────────┐ │         │    │
│    │              │  │ IMMEDIATE RETRY (0ms delay)        │ │         │    │
│    │              │  │ Get next available instance        │ │         │    │
│    │              │  └────────────────────────────────────┘ │         │    │
│    │              │               │              │          │         │    │
│    │              │ getRemotes()  │              │          │         │    │
│    │              │──────────────►│              │          │         │    │
│    │              │               │ getByLoad()  │          │         │    │
│    │              │               │─────────────►│          │         │    │
│    │              │               │ Instance B   │          │         │    │
│    │              │               │◄─────────────│          │         │    │
│    │              │ [Remote B]    │              │          │         │    │
│    │              │◄──────────────│              │          │         │    │
│    │              │               │              │          │         │    │
│    │              │           GET /refdata       │          │         │    │
│    │              │────────────────────────────────────────────────────►    │
│    │              │               │              │          │         │    │
│    │              │               │              │  200 OK  │         │    │
│    │              │◄────────────────────────────────────────────────────    │
│    │              │               │              │          │         │    │
│    │  200 OK      │               │              │          │         │    │
│    │◄─────────────│               │              │          │         │    │
│    │              │               │              │          │         │    │
│                                                                             │
│  Timeline:                                                                  │
│  ─────────                                                                  │
│  T+0ms    : Request to local                                               │
│  T+50ms   : Local fails (connection refused)                               │
│  T+50ms   : Mark local UNHEALTHY (passive health)                          │
│  T+51ms   : Retry to remote (immediate, no delay)                          │
│  T+100ms  : Response from remote                                           │
│                                                                             │
│  Total latency: ~100ms (vs 10+ seconds with traditional approach)          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Scenario 3: Local Fails → Remote Fails (Stale Cache) → Next Remote Succeeds

```
┌─────────────────────────────────────────────────────────────────────────────┐
│            STALE CACHE - BOTH LOCAL AND FIRST REMOTE FAIL                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Client    TradeReceiver   Registry    Local   Remote1   Remote2           │
│    │            │             │          │        │         │              │
│    │ POST       │             │          │        │         │              │
│    │───────────►│             │          │        │         │              │
│    │            │             │          │        │         │              │
│    │            │   GET /refdata         │        │         │              │
│    │            │────────────────────────►│        │         │              │
│    │            │   Connection Refused   │        │         │              │
│    │            │◄────────────────────────│        │         │              │
│    │            │             │          │        │         │              │
│    │            │ markUnhealthy(Local)   │        │         │              │
│    │            │────────────►│          │        │         │              │
│    │            │             │          │        │         │              │
│    │            │ ─ ─ ─ Retry 1 (immediate) ─ ─ ─ │         │              │
│    │            │             │          │        │         │              │
│    │            │   GET /refdata (to Remote1)     │         │              │
│    │            │─────────────────────────────────►│         │              │
│    │            │   Connection Refused   │        │         │              │
│    │            │◄─────────────────────────────────│         │              │
│    │            │             │          │        │         │              │
│    │            │ markUnhealthy(Remote1) │        │         │              │
│    │            │────────────►│          │        │         │              │
│    │            │             │          │        │         │              │
│    │            │ ─ ─ ─ Retry 2 (immediate) ─ ─ ─ ─ ─ ─ ─ ─│              │
│    │            │             │          │        │         │              │
│    │            │   GET /refdata (to Remote2)     │         │              │
│    │            │───────────────────────────────────────────►│              │
│    │            │             │          │        │         │              │
│    │            │   200 OK    │          │        │         │              │
│    │            │◄───────────────────────────────────────────│              │
│    │            │             │          │        │         │              │
│    │ 200 OK     │             │          │        │         │              │
│    │◄───────────│             │          │        │         │              │
│    │            │             │          │        │         │              │
│                                                                             │
│  Registry State After:                                                      │
│  ┌──────────────────────────────────────────────────────────┐              │
│  │ Instance    │ Health     │ Updated By                    │              │
│  ├─────────────┼────────────┼───────────────────────────────┤              │
│  │ Local       │ UNHEALTHY  │ Passive (request failed)      │              │
│  │ Remote1     │ UNHEALTHY  │ Passive (request failed)      │              │
│  │ Remote2     │ HEALTHY    │ -                             │              │
│  └──────────────────────────────────────────────────────────┘              │
│                                                                             │
│  Key Point: Stale cache for Remote1 was corrected by actual request        │
│             failure, not by waiting for background health check.           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Scenario 4: All Instances Fail → Circuit Breaker Opens

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              ALL INSTANCES FAIL - CIRCUIT BREAKER OPENS                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Client    TradeReceiver   Registry   CircuitBreaker   Local   Remote      │
│    │            │             │             │            │        │        │
│    │ POST       │             │             │            │        │        │
│    │───────────►│             │             │            │        │        │
│    │            │             │             │            │        │        │
│    │            │   GET /refdata (Local)    │            │        │        │
│    │            │─────────────────────────────────────────►│        │        │
│    │            │   Connection Refused      │            │        │        │
│    │            │◄─────────────────────────────────────────│        │        │
│    │            │ markUnhealthy(Local)      │            │        │        │
│    │            │────────────►│             │            │        │        │
│    │            │             │             │            │        │        │
│    │            │   GET /refdata (Remote)   │            │        │        │
│    │            │──────────────────────────────────────────────────►│        │
│    │            │   Connection Refused      │            │        │        │
│    │            │◄──────────────────────────────────────────────────│        │
│    │            │ markUnhealthy(Remote)     │            │        │        │
│    │            │────────────►│             │            │        │        │
│    │            │             │             │            │        │        │
│    │            │ recordFailure()           │            │        │        │
│    │            │───────────────────────────►│            │        │        │
│    │            │             │             │            │        │        │
│    │            │             │  ┌────────────────────┐  │        │        │
│    │            │             │  │ Failure threshold  │  │        │        │
│    │            │             │  │ exceeded (50%)     │  │        │        │
│    │            │             │  │ CIRCUIT NOW OPEN   │  │        │        │
│    │            │             │  └────────────────────┘  │        │        │
│    │            │             │             │            │        │        │
│    │  503 Error │             │             │            │        │        │
│    │◄───────────│             │             │            │        │        │
│    │  "Service  │             │             │            │        │        │
│    │ Unavailable│             │             │            │        │        │
│    │            │             │             │            │        │        │
│                                                                             │
│                                                                             │
│  SUBSEQUENT REQUESTS (Circuit Open):                                        │
│  ───────────────────────────────────                                        │
│                                                                             │
│  Client    TradeReceiver   CircuitBreaker                                  │
│    │            │               │                                          │
│    │ POST       │               │                                          │
│    │───────────►│               │                                          │
│    │            │ isOpen()?     │                                          │
│    │            │──────────────►│                                          │
│    │            │    true       │                                          │
│    │            │◄──────────────│                                          │
│    │            │               │                                          │
│    │  503 Error │  ┌─────────────────────────────┐                         │
│    │◄───────────│  │ FAST FAIL - No actual call │                         │
│    │ (instant)  │  │ Protects downstream systems │                         │
│    │            │  └─────────────────────────────┘                         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Scenario 5: Instance Recovery via Background Health Check

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                 INSTANCE RECOVERY - BACKGROUND HEALTH CHECK                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  HealthMonitor    Registry    Local Instance                               │
│       │              │              │                                      │
│       │              │              │     Instance was UNHEALTHY           │
│       │              │              │     (marked by passive health)       │
│       │              │              │                                      │
│  ─ ─ ─ Every 30 seconds ─ ─ ─ ─ ─ ─│                                      │
│       │              │              │                                      │
│       │    GET /actuator/health     │                                      │
│       │─────────────────────────────►│                                      │
│       │              │              │                                      │
│       │              │              │     Instance has recovered!          │
│       │              │              │                                      │
│       │    200 OK {"status":"UP"}   │                                      │
│       │◄─────────────────────────────│                                      │
│       │              │              │                                      │
│       │ markHealthy(Local)          │                                      │
│       │─────────────►│              │                                      │
│       │              │              │                                      │
│       │              │  ┌─────────────────────────────┐                    │
│       │              │  │ Instance now available for  │                    │
│       │              │  │ load balancer selection     │                    │
│       │              │  └─────────────────────────────┘                    │
│       │              │              │                                      │
│                                                                             │
│  Key Point: Background health monitor's only job is to detect RECOVERY.    │
│             Failure detection is handled by passive health (real-time).    │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Retry Flow Decision Tree

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         RETRY DECISION FLOW                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│                           ┌─────────────┐                                   │
│                           │   Request   │                                   │
│                           │   Arrives   │                                   │
│                           └──────┬──────┘                                   │
│                                  │                                          │
│                                  ▼                                          │
│                    ┌─────────────────────────┐                              │
│                    │ Circuit Breaker OPEN?   │                              │
│                    └───────────┬─────────────┘                              │
│                          │           │                                      │
│                         YES          NO                                     │
│                          │           │                                      │
│                          ▼           ▼                                      │
│              ┌───────────────┐  ┌─────────────────────────┐                │
│              │  FAST FAIL    │  │ Get LOCAL instance      │                │
│              │  503 Error    │  │ from registry           │                │
│              └───────────────┘  └───────────┬─────────────┘                │
│                                             │                               │
│                                             ▼                               │
│                              ┌─────────────────────────┐                    │
│                              │ LOCAL instance healthy? │                    │
│                              └───────────┬─────────────┘                    │
│                                    │           │                            │
│                                   YES          NO                           │
│                                    │           │                            │
│                                    ▼           │                            │
│                          ┌─────────────────┐   │                            │
│                          │ Call LOCAL      │   │                            │
│                          └────────┬────────┘   │                            │
│                                   │            │                            │
│                                   ▼            │                            │
│                    ┌─────────────────────────┐ │                            │
│                    │      Success?           │ │                            │
│                    └───────────┬─────────────┘ │                            │
│                          │           │         │                            │
│                         YES          NO        │                            │
│                          │           │         │                            │
│                          ▼           ▼         │                            │
│              ┌───────────────┐  ┌─────────────────────────┐                │
│              │ Return        │  │ Mark LOCAL UNHEALTHY    │◄───────────────┘
│              │ Response      │  │ (Passive Health Update) │                 │
│              └───────────────┘  └───────────┬─────────────┘                │
│                                             │                               │
│                                             ▼                               │
│                              ┌─────────────────────────┐                    │
│                              │ Get REMOTE instances    │                    │
│                              │ (sorted by load)        │                    │
│                              └───────────┬─────────────┘                    │
│                                          │                                  │
│                                          ▼                                  │
│                            ┌───────────────────────────┐                    │
│                            │ Any healthy REMOTE left?  │                    │
│                            └───────────┬───────────────┘                    │
│                                  │           │                              │
│                                 YES          NO                             │
│                                  │           │                              │
│                                  ▼           ▼                              │
│                      ┌─────────────────┐  ┌─────────────────┐              │
│                      │ Call REMOTE     │  │ All exhausted   │              │
│                      └────────┬────────┘  │ Record failure  │              │
│                               │           │ in CircuitBreaker│              │
│                               ▼           └────────┬────────┘              │
│                ┌─────────────────────────┐         │                        │
│                │      Success?           │         ▼                        │
│                └───────────┬─────────────┘  ┌─────────────────┐            │
│                      │           │          │ Return 503      │            │
│                     YES          NO         │ Service         │            │
│                      │           │          │ Unavailable     │            │
│                      ▼           │          └─────────────────┘            │
│          ┌───────────────┐       │                                         │
│          │ Return        │       │                                         │
│          │ Response      │       │                                         │
│          └───────────────┘       │                                         │
│                                  │                                         │
│                                  ▼                                         │
│                   ┌─────────────────────────┐                              │
│                   │ Mark REMOTE UNHEALTHY   │                              │
│                   │ Loop to next REMOTE     │──────────┐                   │
│                   └─────────────────────────┘          │                   │
│                                  ▲                     │                   │
│                                  └─────────────────────┘                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Configuration

### Recommended Settings

```yaml
# Application Configuration
resilience4j:
  retry:
    instances:
      referenceLookup:
        max-attempts: 3                    # Try up to 3 instances
        wait-duration: 0ms                 # NO DELAY between retries (immediate failover)
        enable-exponential-backoff: false  # Don't wait
        retry-exceptions:
          - java.net.ConnectException
          - java.net.SocketTimeoutException
          - feign.RetryableException

  circuitbreaker:
    instances:
      referenceLookup:
        failure-rate-threshold: 50         # Open after 50% failures
        minimum-number-of-calls: 5         # Need at least 5 calls to evaluate
        sliding-window-size: 10            # Look at last 10 calls
        wait-duration-in-open-state: 30s   # Stay open for 30 seconds
        permitted-calls-in-half-open: 3    # Allow 3 test calls in half-open
        automatic-transition-from-open-to-half-open-enabled: true

# Background Health Monitor (for RECOVERY detection only)
health:
  monitor:
    enabled: true
    interval: 30                           # 30 seconds (since failures caught in real-time)
    timeout: 5                             # 5 second timeout for health check

# Eureka Sync (populates registry from Eureka)
eureka:
  sync:
    interval: 30000                        # 30 seconds
    services: reference-lookup-service,check-eligible-service
```

---

## Component Responsibilities

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    COMPONENT RESPONSIBILITIES                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ LocalFirstLoadBalancer                                               │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │ • Select LOCAL healthy instance first                               │   │
│  │ • Fallback to REMOTE instances sorted by load                       │   │
│  │ • Skip instances marked UNHEALTHY                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ ResilientRetryHandler                                                │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │ • Catch connection/timeout exceptions                               │   │
│  │ • Mark failed instance as UNHEALTHY (passive health)                │   │
│  │ • Immediately retry with next instance (0ms delay)                  │   │
│  │ • Record failures in circuit breaker                                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ ServiceInstanceRegistry                                              │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │ • Store instance health status (thread-safe)                        │   │
│  │ • Provide healthy instances to load balancer                        │   │
│  │ • Accept health updates from passive + active sources               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ HealthMonitorService (Background)                                    │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │ • Run every 30 seconds                                              │   │
│  │ • Check UNHEALTHY instances for recovery                            │   │
│  │ • Mark recovered instances as HEALTHY                               │   │
│  │ • NOT responsible for failure detection (passive health does that)  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ CircuitBreaker (Per Instance)                                        │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │ • Track failure rate per instance                                   │   │
│  │ • OPEN: Fast-fail without calling instance                          │   │
│  │ • HALF-OPEN: Allow test calls to check recovery                     │   │
│  │ • CLOSED: Normal operation                                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Comparison: Before vs After

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    BEFORE vs AFTER PASSIVE HEALTH                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  METRIC                    BEFORE              AFTER                        │
│  ────────────────────────  ──────────────────  ─────────────────────────   │
│                                                                             │
│  Failover latency          10+ seconds         < 100ms                     │
│                            (wait for health    (immediate retry)           │
│                             monitor)                                        │
│                                                                             │
│  Stale cache handling      Wait for next       Corrected by actual         │
│                            health check        request failure             │
│                                                                             │
│  Health update source      Background only     Background + Passive        │
│                                                                             │
│  Recovery detection        10s interval        30s interval                │
│                                                (failures caught real-time) │
│                                                                             │
│  User experience           Request fails       Request succeeds            │
│  (when local down)         (must retry)        (transparent failover)      │
│                                                                             │
│  System load               Extra health        Failures = health signals   │
│                            check threads       (no extra overhead)         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Implementation Checklist

- [x] Modify `LocalFirstLoadBalancer` to return ordered list of instances
  - Updated to track tried instances via `PassiveHealthContext`
  - Excludes already-tried instances during retry cycle
- [x] Create `ResilientRetryHandler` for passive health updates
  - Implemented as `PassiveHealthRetryEventListener` using Resilience4j events
  - Marks instances unhealthy on retry events
- [x] Update `ServiceInstanceRegistry` to accept passive health updates
  - Added `markUnhealthy()` and `markHealthy()` methods with logging
- [x] Configure Resilience4j retry with 0ms delay
  - Updated both trade-receiver-service and eq-trade-handler-service
  - Disabled exponential backoff for immediate failover
- [ ] Configure per-instance circuit breakers
  - Not required: Service-level circuit breakers work with passive health
  - Instance failover handled by PassiveHealthContext
- [x] Update `HealthMonitorService` interval to 30s (recovery only)
  - Background health checks now focused on recovery detection
- [x] Add metrics for failover events
  - Logging added for failover events and health transitions
- [x] Add logging for passive health updates
  - WARN level for UNHEALTHY transitions
  - INFO level for RECOVERY transitions

### Implementation Files

| Component | File |
|-----------|------|
| PassiveHealthContext | `common/src/main/java/.../resilience/PassiveHealthContext.java` |
| PassiveHealthRetryEventListener | `common/src/main/java/.../resilience/PassiveHealthRetryEventListener.java` |
| LocalFirstLoadBalancer (updated) | `common/src/main/java/.../loadbalancer/LocalFirstLoadBalancer.java` |
| ServiceInstanceRegistry (updated) | `common/src/main/java/.../registry/ServiceInstanceRegistry.java` |
| Configuration (trade-receiver) | `trade-receiver-service/src/main/resources/application.yml` |
| Configuration (eq-trade-handler) | `eq-trade-handler-service/src/main/resources/application.yml` |

---

## References

- [Google SRE Book - Handling Overload](https://sre.google/sre-book/handling-overload/)
- [Netflix Hystrix - Circuit Breaker Pattern](https://github.com/Netflix/Hystrix/wiki)
- [Resilience4j Documentation](https://resilience4j.readme.io/)
- [Martin Fowler - Circuit Breaker](https://martinfowler.com/bliki/CircuitBreaker.html)
