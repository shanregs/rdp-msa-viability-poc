# RDP-MSA Viability POC - Testing Guide

This guide provides comprehensive instructions for testing the microservices architecture, including API endpoints, resilience patterns, and failure scenarios.

---

## Table of Contents
1. [API Endpoint Testing](#api-endpoint-testing)
2. [Resilience Pattern Testing](#resilience-pattern-testing)
3. [Load Balancing Verification](#load-balancing-verification)
4. [Health Monitoring Testing](#health-monitoring-testing)
5. [Failure Simulation](#failure-simulation)
6. [Expected Behaviors](#expected-behaviors)

---

## API Endpoint Testing

### Reference Lookup Service

```bash
# Get instrument reference data
curl -X GET "http://localhost:8081/api/v1/reference/instruments/AAPL" \
  -H "Accept: application/json"

# Expected response:
# {
#   "instrumentId": "AAPL",
#   "name": "Apple Inc.",
#   "type": "EQUITY",
#   "currency": "USD",
#   "exchange": "NASDAQ"
# }

# Get counterparty reference data
curl -X GET "http://localhost:8081/api/v1/reference/counterparties/JPM" \
  -H "Accept: application/json"

# Expected response:
# {
#   "counterpartyId": "JPM",
#   "name": "JPMorgan Chase",
#   "lei": "8I5DZWZKVSZI1NUHU748",
#   "country": "US"
# }
```

### Check Eligible Service

```bash
# Check trade eligibility
curl -X POST "http://localhost:8092/api/v1/eligibility/check" \
  -H "Content-Type: application/json" \
  -d '{
    "tradeType": "EQUITY",
    "instrument": "AAPL",
    "counterparty": "JPM",
    "quantity": 100,
    "notionalValue": 15050.00
  }'

# Expected response:
# {
#   "eligible": true,
#   "reason": null,
#   "regulatoryFlags": []
# }
```

### Trade Receiver Service

```bash
# Submit a trade (equity profile - Server 1)
curl -X POST "http://localhost:8082/api/v1/trades" \
  -H "Content-Type: application/json" \
  -d '{
    "tradeId": "TRD-001",
    "tradeType": "EQUITY",
    "instrument": "AAPL",
    "counterparty": "JPM",
    "quantity": 100,
    "price": 150.50,
    "currency": "USD",
    "tradeDate": "2024-01-15",
    "settlementDate": "2024-01-17"
  }'

# Expected response:
# {
#   "tradeId": "TRD-001",
#   "status": "RECEIVED",
#   "timestamp": "2024-01-15T10:30:00Z",
#   "eligible": true,
#   "processingNode": "server1"
# }

# Submit a forex trade (Server 2)
curl -X POST "http://server2:8083/api/v1/trades" \
  -H "Content-Type: application/json" \
  -d '{
    "tradeId": "FX-001",
    "tradeType": "FOREX",
    "instrument": "EUR/USD",
    "counterparty": "CITI",
    "quantity": 1000000,
    "price": 1.0850,
    "currency": "USD",
    "tradeDate": "2024-01-15",
    "settlementDate": "2024-01-17"
  }'
```

### EQ Trade Handler Service

```bash
# Get processed trades
curl -X GET "http://server2:8090/api/v1/processed-trades" \
  -H "Accept: application/json"

# Get trade by ID
curl -X GET "http://server2:8090/api/v1/processed-trades/TRD-001" \
  -H "Accept: application/json"
```

---

## Resilience Pattern Testing

### 1. Passive Health with Immediate Retry

The system uses **Passive Health** pattern for real-time failure detection:
- **0ms retry delay** - Immediate failover to next instance
- **Passive health updates** - Failed instances marked unhealthy during request
- **Instance skip** - Already-tried instances excluded from retry cycle

#### Test Steps:

1. **Stop reference-lookup-service temporarily:**
   ```bash
   # Stop reference-lookup on Server 1
   docker exec server1 supervisorctl stop reference-lookup-service
   ```

2. **Send a trade request:**
   ```bash
   curl -X POST "http://localhost:8082/api/v1/trades" \
     -H "Content-Type: application/json" \
     -d '{
       "tradeId": "PASSIVE-HEALTH-001",
       "tradeType": "EQUITY",
       "instrument": "AAPL",
       "counterparty": "JPM",
       "quantity": 100,
       "price": 150.50,
       "currency": "USD"
     }'
   ```

3. **Observe passive health and retry logs:**
   ```bash
   docker logs -f server1 | grep -E "PASSIVE|UNHEALTHY|FAILOVER|retry"
   ```

4. **Expected log pattern:**
   ```
   WARN  - PASSIVE HEALTH: Instance reference-lookup:server1:8081 marked UNHEALTHY
   INFO  - PASSIVE HEALTH FAILOVER: referenceLookup retry #1 - instance marked unhealthy, trying next
   INFO  - Selected REMOTE instance for reference-lookup-service (load=0): reference-lookup:server3:8081
   INFO  - Reference lookup successful (remote)
   ```

5. **Verify immediate failover time:**
   - Total request latency should be < 500ms (vs 3+ seconds with traditional retry)
   - No artificial delay between retry attempts

6. **Restart service:**
   ```bash
   docker exec server1 supervisorctl start reference-lookup-service
   ```

---

### 2. Circuit Breaker Testing

Circuit breaker configuration:
- Failure rate threshold: 50%
- Wait duration in open state: 30 seconds
- Sliding window size: 10 calls

#### Test Steps:

1. **Check initial circuit breaker status:**
   ```bash
   curl -s "http://localhost:8082/actuator/circuitbreakers" | jq .

   # Expected: state = CLOSED
   ```

2. **Stop dependent service:**
   ```bash
   docker exec server1 supervisorctl stop reference-lookup-service
   docker exec server3 supervisorctl stop reference-lookup-service
   ```

3. **Send rapid requests to trigger circuit breaker (10+ requests):**
   ```bash
   for i in {1..15}; do
     curl -s -X POST "http://localhost:8082/api/v1/trades" \
       -H "Content-Type: application/json" \
       -d "{\"tradeId\":\"CB-TEST-$i\",\"tradeType\":\"EQUITY\",\"instrument\":\"AAPL\",\"counterparty\":\"JPM\",\"quantity\":100,\"price\":150.50,\"currency\":\"USD\"}" &
   done
   wait
   ```

4. **Verify circuit is OPEN:**
   ```bash
   curl -s "http://localhost:8082/actuator/circuitbreakers" | jq .

   # Expected: state = OPEN
   ```

5. **Observe fast-fail behavior:**
   ```bash
   # This should fail immediately without retries
   curl -X POST "http://localhost:8082/api/v1/trades" \
     -H "Content-Type: application/json" \
     -d '{"tradeId":"CB-OPEN-TEST","tradeType":"EQUITY","instrument":"AAPL","counterparty":"JPM","quantity":100,"price":150.50,"currency":"USD"}'

   # Expected: Immediate failure response
   # {"error": "CircuitBreaker 'referenceLookup' is OPEN"}
   ```

6. **Wait for HALF_OPEN state (30 seconds):**
   ```bash
   sleep 30
   curl -s "http://localhost:8082/actuator/circuitbreakers" | jq .

   # Expected: state = HALF_OPEN
   ```

7. **Restart services and verify circuit closes:**
   ```bash
   docker exec server1 supervisorctl start reference-lookup-service
   docker exec server3 supervisorctl start reference-lookup-service

   # Send successful request
   curl -X POST "http://localhost:8082/api/v1/trades" \
     -H "Content-Type: application/json" \
     -d '{"tradeId":"CB-RECOVERY-TEST","tradeType":"EQUITY","instrument":"AAPL","counterparty":"JPM","quantity":100,"price":150.50,"currency":"USD"}'

   # Check circuit is CLOSED
   curl -s "http://localhost:8082/actuator/circuitbreakers" | jq .
   ```

---

### 3. Timeout Handling

Timeout configuration: 5 seconds per request

#### Test Steps:

1. **Simulate slow response (if endpoint supports delay):**
   ```bash
   # If reference-lookup has a delay parameter
   curl -X GET "http://localhost:8081/api/v1/reference/instruments/AAPL?delay=6000"
   ```

2. **Expected behavior:**
   - Request times out after 5 seconds
   - Retry to next available instance
   - Log shows timeout and failover

---

## Load Balancing Verification

### Local-First Load Balancing Test

#### Test Steps:

1. **Verify local instance is preferred:**

   Send request from Server 1 trade-receiver to reference-lookup:
   ```bash
   curl -X POST "http://localhost:8082/api/v1/trades" \
     -H "Content-Type: application/json" \
     -d '{"tradeId":"LB-LOCAL-001","tradeType":"EQUITY","instrument":"AAPL","counterparty":"JPM","quantity":100,"price":150.50,"currency":"USD"}'
   ```

2. **Check logs for local routing:**
   ```bash
   docker logs server1 | grep -E "LocalFirst|routing|instance"

   # Expected:
   # INFO - LocalFirstLoadBalancer: Selected local instance on server1
   ```

3. **Stop local instance:**
   ```bash
   docker exec server1 supervisorctl stop reference-lookup-service
   ```

4. **Send another request:**
   ```bash
   curl -X POST "http://localhost:8082/api/v1/trades" \
     -H "Content-Type: application/json" \
     -d '{"tradeId":"LB-REMOTE-001","tradeType":"EQUITY","instrument":"AAPL","counterparty":"JPM","quantity":100,"price":150.50,"currency":"USD"}'
   ```

5. **Verify failover to remote:**
   ```bash
   docker logs server1 | grep -E "LocalFirst|routing|instance"

   # Expected:
   # INFO - LocalFirstLoadBalancer: Local instance unavailable, selecting remote on server3
   ```

6. **Restart local instance:**
   ```bash
   docker exec server1 supervisorctl start reference-lookup-service
   ```

7. **Verify local routing resumes:**
   ```bash
   curl -X POST "http://localhost:8082/api/v1/trades" \
     -H "Content-Type: application/json" \
     -d '{"tradeId":"LB-LOCAL-002","tradeType":"EQUITY","instrument":"AAPL","counterparty":"JPM","quantity":100,"price":150.50,"currency":"USD"}'

   docker logs server1 | tail -20 | grep -E "LocalFirst|routing"
   # Should show local instance selected again
   ```

---

## Health Monitoring Testing

### Background Health Check (Recovery Detection)

With the **Passive Health** pattern, the background health monitor focuses on **recovery detection**:
- **Failure detection**: Handled by passive health (real-time, during requests)
- **Recovery detection**: Handled by background health monitor (30-second interval)

#### Test Steps:

1. **Observe health check logs:**
   ```bash
   docker logs -f server1 | grep -E "HealthMonitor|RECOVERY"

   # Expected pattern every 30 seconds:
   # INFO - HealthMonitor: Checking instance health for recovery
   # INFO - HealthMonitor: reference-lookup-service@server1 - HEALTHY
   # INFO - HealthMonitor: reference-lookup-service@server3 - HEALTHY
   ```

2. **Stop a service and trigger passive health marking:**
   ```bash
   docker exec server3 supervisorctl stop reference-lookup-service

   # Send a request to trigger passive health marking
   curl -X POST "http://localhost:8082/api/v1/trades" \
     -H "Content-Type: application/json" \
     -d '{"tradeId":"HEALTH-TEST","tradeType":"EQUITY","instrument":"AAPL","counterparty":"JPM","quantity":100,"price":150.50,"currency":"USD"}'

   docker logs server1 | tail -30 | grep -E "PASSIVE|UNHEALTHY"

   # Expected (immediate, during request):
   # WARN - PASSIVE HEALTH: Instance reference-lookup:server3:8081 marked UNHEALTHY
   ```

3. **Restart service and verify recovery detection:**
   ```bash
   docker exec server3 supervisorctl start reference-lookup-service

   # Wait for background health monitor to detect recovery (up to 30 seconds)
   sleep 35

   docker logs server1 | tail -30 | grep -E "RECOVERY|marked HEALTHY"

   # Expected:
   # INFO - RECOVERY: Instance reference-lookup:server3:8081 marked HEALTHY
   ```

---

## Failure Simulation

### Simulating Service Failures

#### 1. Single Service Failure
```bash
# Stop reference-lookup on Server 1
docker exec server1 supervisorctl stop reference-lookup-service

# Verify service is down
curl -s "http://localhost:8081/actuator/health"
# Expected: Connection refused
```

#### 2. Multiple Service Failures
```bash
# Stop reference-lookup on both servers
docker exec server1 supervisorctl stop reference-lookup-service
docker exec server3 supervisorctl stop reference-lookup-service

# All requests to reference-lookup should fail
# Circuit breaker should open
```

#### 3. Network Partition (Simulated)
```bash
# Disconnect server3 from network
docker network disconnect rdp-network server3

# After testing, reconnect
docker network connect rdp-network server3
```

#### 4. Container Crash
```bash
# Kill and restart container
docker kill server3
docker compose -f docker/server3/docker-compose.yml up -d
```

---

## Expected Behaviors

### Normal Operation
| Scenario | Expected Behavior |
|----------|-------------------|
| Trade submission | Processed successfully, returns RECEIVED status |
| Reference lookup | Returns instrument/counterparty data |
| Eligibility check | Returns eligible:true for valid trades |

### Failure Scenarios
| Scenario | Expected Behavior |
|----------|-------------------|
| Local service down | Automatic failover to remote instance |
| All instances down | Circuit breaker opens, fast-fail response |
| Slow response | Timeout after 5s, retry to next instance |
| Service recovery | Circuit breaker closes, routing resumes |

### Log Patterns to Monitor

#### Successful Processing
```
INFO - Received trade TRD-001
DEBUG - Selected LOCAL instance for reference-lookup-service: reference-lookup:server1:8081
INFO - Reference lookup successful for AAPL
INFO - Eligibility check passed
INFO - Trade TRD-001 processed successfully
```

#### Passive Health Failover
```
WARN - PASSIVE HEALTH: Instance reference-lookup:server1:8081 marked UNHEALTHY
INFO - PASSIVE HEALTH FAILOVER: referenceLookup retry #1 - instance marked unhealthy, trying next
DEBUG - Skipping LOCAL instance reference-lookup:server1:8081 (already tried in this retry cycle)
DEBUG - Selected REMOTE instance for reference-lookup-service (load=0): reference-lookup:server3:8081
INFO - PASSIVE HEALTH: referenceLookup succeeded after 1 retries
INFO - Reference lookup successful (remote)
```

#### All Instances Exhausted
```
WARN - PASSIVE HEALTH: Instance reference-lookup:server1:8081 marked UNHEALTHY
WARN - PASSIVE HEALTH: Instance reference-lookup:server3:8081 marked UNHEALTHY
ERROR - PASSIVE HEALTH: referenceLookup exhausted all 3 retries. Tried instances: [reference-lookup:server1:8081, reference-lookup:server3:8081]
WARN - No healthy instances available for service: reference-lookup-service (tried: [...])
```

#### Circuit Breaker Open
```
ERROR - CircuitBreaker 'referenceLookup' is OPEN
ERROR - Request rejected, circuit breaker preventing calls
WARN - Returning fallback response
```

#### Recovery Detection
```
INFO - RECOVERY: Instance reference-lookup:server1:8081 marked HEALTHY
INFO - CircuitBreaker 'referenceLookup' transitioning to HALF_OPEN
INFO - Probe request to reference-lookup successful
INFO - CircuitBreaker 'referenceLookup' transitioning to CLOSED
```

---

## Quick Test Commands

```bash
# Health check all services
for port in 8081 8082 8088 8092; do
  echo "Port $port: $(curl -s -o /dev/null -w '%{http_code}' http://localhost:$port/actuator/health)"
done

# Submit test trade
curl -X POST "http://localhost:8082/api/v1/trades" \
  -H "Content-Type: application/json" \
  -d '{"tradeId":"QUICK-TEST","tradeType":"EQUITY","instrument":"AAPL","counterparty":"JPM","quantity":100,"price":150.50,"currency":"USD"}'

# Check circuit breaker status
curl -s "http://localhost:8082/actuator/circuitbreakers" | jq '.circuitBreakers | to_entries[] | {name: .key, state: .value.state}'

# Check Eureka registrations
curl -s "http://localhost:8088/eureka/apps" | grep -oP '(?<=<app>)[^<]+'
```

---

## Passive Health Retry Test Flows

This section provides detailed test flow diagrams and verified test scenarios for the Passive Health with Immediate Retry pattern.

### Test Flow 1: Passive Health Immediate Failover

**Objective**: Verify that when a local instance fails, the system immediately marks it unhealthy and retries on a remote instance with 0ms delay.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                    TEST FLOW: PASSIVE HEALTH IMMEDIATE FAILOVER                             │
│                                                                                             │
│  SETUP:                                                                                     │
│  ─────────────────────────────────────────────────────────────────────────────────────────  │
│  • Server1: trade-receiver-service (port 8082) + reference-lookup-service (port 8081)      │
│  • Server3: reference-lookup-service (port 8081) - remote instance                         │
│  • Local reference-lookup is PAUSED (SIGSTOP) to simulate failure                          │
│                                                                                             │
│  ┌─────────────┐                                                                            │
│  │   Client    │                                                                            │
│  └──────┬──────┘                                                                            │
│         │                                                                                   │
│         │ T+0ms: POST /api/v1/trades                                                        │
│         ▼                                                                                   │
│  ┌──────────────────────────────────────────────────────────────────────────────────────┐   │
│  │                           TRADE RECEIVER SERVICE (Server1)                            │   │
│  │                                                                                       │   │
│  │  ┌─────────────────────────────────────────────────────────────────────────────────┐ │   │
│  │  │ T+1ms: TradeReceiverService.processTrade()                                       │ │   │
│  │  │        → Calls reference-lookup-service                                          │ │   │
│  │  └─────────────────────────────────────────────────────────────────────────────────┘ │   │
│  │                                  │                                                    │   │
│  │                                  ▼                                                    │   │
│  │  ┌─────────────────────────────────────────────────────────────────────────────────┐ │   │
│  │  │ T+2ms: LocalFirstLoadBalancer.choose()                                           │ │   │
│  │  │        → PassiveHealthContext: triedInstances = []                               │ │   │
│  │  │        → SELECTED: LOCAL instance (reference-lookup:server1:8081)                │ │   │
│  │  │        → context.setCurrentInstance("reference-lookup", "server1:8081")          │ │   │
│  │  └─────────────────────────────────────────────────────────────────────────────────┘ │   │
│  │                                  │                                                    │   │
│  │                                  ▼                                                    │   │
│  │  ┌─────────────────────────────────────────────────────────────────────────────────┐ │   │
│  │  │ T+3ms → T+10003ms: Feign Client attempts connection to LOCAL instance            │ │   │
│  │  │                                                                                  │ │   │
│  │  │        ╔═══════════════════════════════════════════════════════════════════════╗ │ │   │
│  │  │        ║  LOCAL INSTANCE (server1:8081) IS PAUSED - NO RESPONSE                ║ │ │   │
│  │  │        ║  Connection hangs until socket timeout (10 seconds)                   ║ │ │   │
│  │  │        ╚═══════════════════════════════════════════════════════════════════════╝ │ │   │
│  │  │                                                                                  │ │   │
│  │  │        T+10003ms: SocketTimeoutException: Read timed out                         │ │   │
│  │  └─────────────────────────────────────────────────────────────────────────────────┘ │   │
│  │                                  │                                                    │   │
│  │                                  ▼                                                    │   │
│  │  ┌─────────────────────────────────────────────────────────────────────────────────┐ │   │
│  │  │ T+10004ms: RESILIENCE4J RETRY EVENT → PassiveHealthRetryEventListener.onRetry() │ │   │
│  │  │                                                                                  │ │   │
│  │  │   ┌─────────────────────────────────────────────────────────────────────────┐   │ │   │
│  │  │   │ PASSIVE HEALTH UPDATE (0ms):                                             │   │ │   │
│  │  │   │                                                                          │   │ │   │
│  │  │   │ 1. context.markTried("reference-lookup:server1:8081")                    │   │ │   │
│  │  │   │ 2. registry.markUnhealthy("reference-lookup", "server1:8081")            │   │ │   │
│  │  │   │                                                                          │   │ │   │
│  │  │   │ LOG: WARN - PASSIVE HEALTH: Instance server1:8081 marked UNHEALTHY       │   │ │   │
│  │  │   │ LOG: INFO - PASSIVE HEALTH FAILOVER: retry #1 - trying next instance     │   │ │   │
│  │  │   └─────────────────────────────────────────────────────────────────────────┘   │ │   │
│  │  └─────────────────────────────────────────────────────────────────────────────────┘ │   │
│  │                                  │                                                    │   │
│  │                                  ▼                                                    │   │
│  │  ┌─────────────────────────────────────────────────────────────────────────────────┐ │   │
│  │  │ T+10005ms: IMMEDIATE RETRY (wait-duration: 0ms)                                  │ │   │
│  │  │                                                                                  │ │   │
│  │  │   LocalFirstLoadBalancer.choose() - RETRY ATTEMPT                                │ │   │
│  │  │   → PassiveHealthContext: triedInstances = ["server1:8081"]                      │ │   │
│  │  │   → Local instance? YES but IN triedInstances → SKIP                             │ │   │
│  │  │   → SELECTED: REMOTE instance (reference-lookup:server3:8081)                    │ │   │
│  │  │   → context.setCurrentInstance("reference-lookup", "server3:8081")               │ │   │
│  │  │                                                                                  │ │   │
│  │  │   LOG: DEBUG - Skipping LOCAL instance server1:8081 (already tried)              │ │   │
│  │  │   LOG: DEBUG - Selected REMOTE instance server3:8081 (load=0)                    │ │   │
│  │  └─────────────────────────────────────────────────────────────────────────────────┘ │   │
│  │                                  │                                                    │   │
│  └──────────────────────────────────┼────────────────────────────────────────────────────┘   │
│                                     │                                                        │
│                                     ▼                                                        │
│  ┌──────────────────────────────────────────────────────────────────────────────────────┐   │
│  │                       REFERENCE LOOKUP SERVICE (Server3 - REMOTE)                     │   │
│  │                                                                                       │   │
│  │  T+10006ms: Receives GET /api/v1/refdata/instrument/AAPL                              │   │
│  │  T+10017ms: Returns 200 OK with instrument data (11ms response time)                  │   │
│  │                                                                                       │   │
│  └──────────────────────────────────────────────────────────────────────────────────────┘   │
│                                     │                                                        │
│                                     ▼                                                        │
│  ┌──────────────────────────────────────────────────────────────────────────────────────┐   │
│  │                           TRADE RECEIVER SERVICE (cont.)                              │   │
│  │                                                                                       │   │
│  │  T+10018ms: PassiveHealthRetryEventListener.onSuccess()                               │   │
│  │             LOG: INFO - PASSIVE HEALTH: referenceLookup succeeded after 1 retries     │   │
│  │             → PassiveHealthContext.clear()                                            │   │
│  │                                                                                       │   │
│  │  T+10019ms: Continue with eligibility check → SUCCESS                                 │   │
│  │  T+10025ms: Return response to client                                                 │   │
│  │                                                                                       │   │
│  └──────────────────────────────────────────────────────────────────────────────────────┘   │
│                                     │                                                        │
│                                     ▼                                                        │
│  ┌─────────────┐                                                                            │
│  │   Client    │  T+10025ms: Receives 200 OK                                                │
│  │             │  Response: { "status": "PROCESSED", "referenceData": { "serverId": "server3" } }
│  └─────────────┘                                                                            │
│                                                                                             │
│  ═══════════════════════════════════════════════════════════════════════════════════════   │
│                                                                                             │
│  TIMELINE SUMMARY:                                                                          │
│  ─────────────────                                                                          │
│  T+0ms      : Request received                                                              │
│  T+2ms      : Selected LOCAL instance                                                       │
│  T+10003ms  : Local timeout (SocketTimeoutException)                                        │
│  T+10004ms  : PASSIVE HEALTH: Mark local UNHEALTHY (0ms)                                    │
│  T+10005ms  : IMMEDIATE RETRY to remote (0ms delay)                                         │
│  T+10017ms  : Remote response received (11ms)                                               │
│  T+10025ms  : Final response to client                                                      │
│                                                                                             │
│  TOTAL FAILOVER TIME: ~22ms (after timeout detection)                                       │
│  VS TRADITIONAL: 3+ seconds (500ms + 1000ms + 2000ms exponential backoff)                   │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Test Flow 2: Circuit Breaker with Passive Health

**Objective**: Verify that when all instances fail, the circuit breaker opens and provides fast-fail with fallback.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                    TEST FLOW: CIRCUIT BREAKER WITH PASSIVE HEALTH                           │
│                                                                                             │
│  SETUP:                                                                                     │
│  ─────────────────────────────────────────────────────────────────────────────────────────  │
│  • ALL reference-lookup instances PAUSED (Server1 and Server3)                              │
│  • Circuit breaker initially CLOSED                                                         │
│                                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                              PHASE 1: FAILURES ACCUMULATE                            │    │
│  │                                                                                      │    │
│  │  Request 1-10: Each request exhausts all 3 retry attempts                            │    │
│  │                                                                                      │    │
│  │  For each request:                                                                   │    │
│  │  ┌────────────────────────────────────────────────────────────────────────────────┐  │    │
│  │  │ Attempt 1 → Local (server1) → TIMEOUT → Mark UNHEALTHY                         │  │    │
│  │  │ Attempt 2 → Remote (server3) → TIMEOUT → Mark UNHEALTHY                        │  │    │
│  │  │ Attempt 3 → No healthy instances → 503 ServiceUnavailable                      │  │    │
│  │  │                                                                                │  │    │
│  │  │ LOG: ERROR - PASSIVE HEALTH: referenceLookup exhausted all 3 retries           │  │    │
│  │  │       Tried instances: [server1:8081, server3:8081]                            │  │    │
│  │  │                                                                                │  │    │
│  │  │ Circuit Breaker: Records FAILURE                                               │  │    │
│  │  └────────────────────────────────────────────────────────────────────────────────┘  │    │
│  │                                                                                      │    │
│  │  After 10 failures:                                                                  │    │
│  │  ┌────────────────────────────────────────────────────────────────────────────────┐  │    │
│  │  │                                                                                │  │    │
│  │  │  Circuit Breaker State:                                                        │  │    │
│  │  │  ├── failureRate: 100%                                                         │  │    │
│  │  │  ├── bufferedCalls: 10                                                         │  │    │
│  │  │  ├── failedCalls: 10                                                           │  │    │
│  │  │  └── state: CLOSED → OPEN                                                      │  │    │
│  │  │                                                                                │  │    │
│  │  │  LOG: WARN - CircuitBreaker 'referenceLookup' state changed: CLOSED → OPEN     │  │    │
│  │  │                                                                                │  │    │
│  │  └────────────────────────────────────────────────────────────────────────────────┘  │    │
│  │                                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                              PHASE 2: FAST-FAIL BEHAVIOR                             │    │
│  │                                                                                      │    │
│  │  ┌─────────────┐                                                                     │    │
│  │  │   Client    │  POST /api/v1/trades                                                │    │
│  │  └──────┬──────┘                                                                     │    │
│  │         │                                                                            │    │
│  │         │ T+0ms                                                                      │    │
│  │         ▼                                                                            │    │
│  │  ┌────────────────────────────────────────────────────────────────────────────────┐  │    │
│  │  │ Trade Receiver Service                                                         │  │    │
│  │  │                                                                                │  │    │
│  │  │  T+1ms: Check Circuit Breaker state                                            │  │    │
│  │  │         → state = OPEN                                                         │  │    │
│  │  │         → REJECT immediately (no downstream call)                              │  │    │
│  │  │                                                                                │  │    │
│  │  │  T+2ms: Return FALLBACK response                                               │  │    │
│  │  │         {                                                                      │  │    │
│  │  │           "status": "PROCESSED",                                               │  │    │
│  │  │           "referenceData": {                                                   │  │    │
│  │  │             "id": "FALLBACK",                                                  │  │    │
│  │  │             "description": "Fallback data - service unavailable"               │  │    │
│  │  │           }                                                                    │  │    │
│  │  │         }                                                                      │  │    │
│  │  │                                                                                │  │    │
│  │  └────────────────────────────────────────────────────────────────────────────────┘  │    │
│  │         │                                                                            │    │
│  │         │ T+67ms (TOTAL!)                                                            │    │
│  │         ▼                                                                            │    │
│  │  ┌─────────────┐                                                                     │    │
│  │  │   Client    │  Response: 200 OK with fallback data                                │    │
│  │  └─────────────┘                                                                     │    │
│  │                                                                                      │    │
│  │  ═══════════════════════════════════════════════════════════════════════════════    │    │
│  │                                                                                      │    │
│  │  FAST-FAIL BENEFIT:                                                                  │    │
│  │  ─────────────────                                                                   │    │
│  │  Response time: 67ms (with fallback)                                                 │    │
│  │  Without circuit breaker: ~20+ seconds (timeout × 2 instances × 3 attempts)          │    │
│  │                                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                              PHASE 3: RECOVERY                                       │    │
│  │                                                                                      │    │
│  │  T+0s:    Services resumed (SIGCONT)                                                 │    │
│  │  T+30s:   Circuit breaker: OPEN → HALF_OPEN (wait-duration-in-open-state: 30s)       │    │
│  │                                                                                      │    │
│  │  T+30s:   Send probe request                                                         │    │
│  │           ┌────────────────────────────────────────────────────────────────────┐     │    │
│  │           │ Request succeeds → bufferedCalls: 1, failedCalls: 0                │     │    │
│  │           └────────────────────────────────────────────────────────────────────┘     │    │
│  │                                                                                      │    │
│  │  T+31s:   Send 2 more probe requests (permitted-calls-in-half-open: 3)               │    │
│  │           ┌────────────────────────────────────────────────────────────────────┐     │    │
│  │           │ Both succeed → Circuit: HALF_OPEN → CLOSED                         │     │    │
│  │           │                                                                    │     │    │
│  │           │ LOG: INFO - CircuitBreaker 'referenceLookup' transitioned to CLOSED│     │    │
│  │           └────────────────────────────────────────────────────────────────────┘     │    │
│  │                                                                                      │    │
│  │  CIRCUIT BREAKER FINAL STATE:                                                        │    │
│  │  ├── failureRate: -1% (reset)                                                        │    │
│  │  ├── bufferedCalls: 0                                                                │    │
│  │  ├── failedCalls: 0                                                                  │    │
│  │  └── state: CLOSED                                                                   │    │
│  │                                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Test Flow 3: Local-First Load Balancing with Passive Health

**Objective**: Verify that the load balancer prefers local instances and correctly fails over to remote when local is unhealthy.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                    TEST FLOW: LOCAL-FIRST LOAD BALANCING                                    │
│                                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                              SCENARIO A: LOCAL AVAILABLE                             │    │
│  │                                                                                      │    │
│  │  Registry State:                                                                     │    │
│  │  ┌──────────────────────────────────────────────────────────────────────────────┐   │    │
│  │  │ reference-lookup-service:                                                     │   │    │
│  │  │   server1:8081 (LOCAL)  - HEALTHY  - load: 0                                  │   │    │
│  │  │   server3:8081 (REMOTE) - HEALTHY  - load: 0                                  │   │    │
│  │  └──────────────────────────────────────────────────────────────────────────────┘   │    │
│  │                                                                                      │    │
│  │  Load Balancer Decision:                                                             │    │
│  │  ┌──────────────────────────────────────────────────────────────────────────────┐   │    │
│  │  │ 1. Get PassiveHealthContext → triedInstances: []                              │   │    │
│  │  │ 2. Get healthy instances: [server1:8081, server3:8081]                        │   │    │
│  │  │ 3. Filter out tried: [server1:8081, server3:8081]                             │   │    │
│  │  │ 4. Find local: server1:8081 ✓                                                 │   │    │
│  │  │ 5. Is local healthy? YES ✓                                                    │   │    │
│  │  │ 6. RETURN: server1:8081 (LOCAL)                                               │   │    │
│  │  │                                                                               │   │    │
│  │  │ LOG: DEBUG - Selected LOCAL instance: reference-lookup:server1:8081           │   │    │
│  │  └──────────────────────────────────────────────────────────────────────────────┘   │    │
│  │                                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                              SCENARIO B: LOCAL UNHEALTHY                             │    │
│  │                                                                                      │    │
│  │  Registry State (after passive health update):                                       │    │
│  │  ┌──────────────────────────────────────────────────────────────────────────────┐   │    │
│  │  │ reference-lookup-service:                                                     │   │    │
│  │  │   server1:8081 (LOCAL)  - UNHEALTHY  - load: 0   ← Marked by passive health   │   │    │
│  │  │   server3:8081 (REMOTE) - HEALTHY    - load: 0                                │   │    │
│  │  └──────────────────────────────────────────────────────────────────────────────┘   │    │
│  │                                                                                      │    │
│  │  Load Balancer Decision:                                                             │    │
│  │  ┌──────────────────────────────────────────────────────────────────────────────┐   │    │
│  │  │ 1. Get PassiveHealthContext → triedInstances: []                              │   │    │
│  │  │ 2. Get healthy instances: [server3:8081]  (server1 excluded - unhealthy)      │   │    │
│  │  │ 3. Filter out tried: [server3:8081]                                           │   │    │
│  │  │ 4. Find local: NONE (server1 is unhealthy)                                    │   │    │
│  │  │ 5. Sort remotes by load: [server3:8081 (load=0)]                              │   │    │
│  │  │ 6. RETURN: server3:8081 (REMOTE, lowest load)                                 │   │    │
│  │  │                                                                               │   │    │
│  │  │ LOG: DEBUG - Selected REMOTE instance (load=0): reference-lookup:server3:8081 │   │    │
│  │  └──────────────────────────────────────────────────────────────────────────────┘   │    │
│  │                                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                              SCENARIO C: RETRY CYCLE (Instance Skip)                 │    │
│  │                                                                                      │    │
│  │  During a retry cycle after local failure:                                           │    │
│  │                                                                                      │    │
│  │  PassiveHealthContext State:                                                         │    │
│  │  ┌──────────────────────────────────────────────────────────────────────────────┐   │    │
│  │  │ triedInstances: ["reference-lookup:server1:8081"]                             │   │    │
│  │  │ currentInstanceId: null (cleared for next selection)                          │   │    │
│  │  └──────────────────────────────────────────────────────────────────────────────┘   │    │
│  │                                                                                      │    │
│  │  Load Balancer Decision (Retry Attempt):                                             │    │
│  │  ┌──────────────────────────────────────────────────────────────────────────────┐   │    │
│  │  │ 1. Get PassiveHealthContext → triedInstances: ["server1:8081"]                │   │    │
│  │  │ 2. Get healthy instances: [server1:8081, server3:8081]                        │   │    │
│  │  │ 3. Filter out tried: [server3:8081]  ← server1 EXCLUDED (already tried)       │   │    │
│  │  │ 4. Find local in filtered: NONE                                               │   │    │
│  │  │ 5. Sort remotes by load: [server3:8081]                                       │   │    │
│  │  │ 6. RETURN: server3:8081                                                       │   │    │
│  │  │                                                                               │   │    │
│  │  │ LOG: DEBUG - Skipping LOCAL instance server1:8081 (already tried)             │   │    │
│  │  │ LOG: DEBUG - Selected REMOTE instance (load=0): server3:8081                  │   │    │
│  │  └──────────────────────────────────────────────────────────────────────────────┘   │    │
│  │                                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Test Flow 4: Recovery Detection by Background Health Monitor

**Objective**: Verify that the background health monitor detects when a previously unhealthy instance has recovered.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                    TEST FLOW: RECOVERY DETECTION                                            │
│                                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                              INITIAL STATE (After Failure)                           │    │
│  │                                                                                      │    │
│  │  Registry State:                                                                     │    │
│  │  ┌──────────────────────────────────────────────────────────────────────────────┐   │    │
│  │  │ reference-lookup-service:                                                     │   │    │
│  │  │   server1:8081 - UNHEALTHY  ← Marked by passive health during request failure │   │    │
│  │  │   server3:8081 - HEALTHY                                                      │   │    │
│  │  └──────────────────────────────────────────────────────────────────────────────┘   │    │
│  │                                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                              RECOVERY PROCESS                                        │    │
│  │                                                                                      │    │
│  │  T+0s:    Service on server1 is restarted/resumed                                    │    │
│  │                                                                                      │    │
│  │  T+30s:   HealthMonitorService scheduled task runs                                   │    │
│  │                                                                                      │    │
│  │  ┌──────────────────────────────────────────────────────────────────────────────┐   │    │
│  │  │ HealthMonitorService.checkUnhealthyInstances():                               │   │    │
│  │  │                                                                               │   │    │
│  │  │   1. Get unhealthy instances: [server1:8081]                                  │   │    │
│  │  │                                                                               │   │    │
│  │  │   2. For server1:8081:                                                        │   │    │
│  │  │      GET http://server1:8081/actuator/health                                  │   │    │
│  │  │                                                                               │   │    │
│  │  │      Response: { "status": "UP" }  ← Service has recovered!                   │   │    │
│  │  │                                                                               │   │    │
│  │  │   3. registry.markHealthy("reference-lookup", "server1:8081")                 │   │    │
│  │  │                                                                               │   │    │
│  │  │      LOG: INFO - RECOVERY: Instance server1:8081 marked HEALTHY               │   │    │
│  │  │                                                                               │   │    │
│  │  └──────────────────────────────────────────────────────────────────────────────┘   │    │
│  │                                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                              FINAL STATE (After Recovery)                            │    │
│  │                                                                                      │    │
│  │  Registry State:                                                                     │    │
│  │  ┌──────────────────────────────────────────────────────────────────────────────┐   │    │
│  │  │ reference-lookup-service:                                                     │   │    │
│  │  │   server1:8081 - HEALTHY  ← Restored by health monitor                        │   │    │
│  │  │   server3:8081 - HEALTHY                                                      │   │    │
│  │  └──────────────────────────────────────────────────────────────────────────────┘   │    │
│  │                                                                                      │    │
│  │  Next request will again prefer LOCAL instance (server1)                             │    │
│  │                                                                                      │    │
│  └─────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                             │
│  ═══════════════════════════════════════════════════════════════════════════════════════   │
│                                                                                             │
│  HEALTH DETECTION RESPONSIBILITIES:                                                         │
│  ──────────────────────────────────                                                         │
│                                                                                             │
│  ┌─────────────────────────────────┐     ┌─────────────────────────────────┐                │
│  │     PASSIVE HEALTH              │     │     BACKGROUND MONITOR          │                │
│  │     (Failure Detection)         │     │     (Recovery Detection)        │                │
│  │                                 │     │                                 │                │
│  │  • Real-time (during requests)  │     │  • Periodic (every 30 seconds)  │                │
│  │  • Marks UNHEALTHY immediately  │     │  • Only checks UNHEALTHY insts  │                │
│  │  • No stale cache               │     │  • Marks HEALTHY on recovery    │                │
│  │  • Zero detection latency       │     │  • Restores traffic routing     │                │
│  │                                 │     │                                 │                │
│  └─────────────────────────────────┘     └─────────────────────────────────┘                │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Verified Test Results Summary

The following test results were verified during the resilience testing session:

| Test | Description | Result | Evidence |
|------|-------------|--------|----------|
| **Passive Health Failover** | Local fails → immediate retry to remote | ✅ PASS | `PASSIVE HEALTH FAILOVER: retry #1 - instance marked unhealthy` |
| **0ms Retry Delay** | No delay between retry attempts | ✅ PASS | Failover in ~22ms after timeout detection |
| **Instance Skip** | Already-tried instances excluded | ✅ PASS | `Skipping LOCAL instance server1:8081 (already tried)` |
| **Circuit Breaker Open** | Opens after 50% failure rate | ✅ PASS | `state: OPEN, failureRate: 100%` |
| **Fast-Fail** | Circuit open → immediate fallback | ✅ PASS | Response in 67ms with fallback data |
| **Circuit Recovery** | OPEN → HALF_OPEN → CLOSED | ✅ PASS | After 30s wait + 3 successful probes |
| **Local-First Preference** | Local instance selected first | ✅ PASS | `Selected LOCAL instance: server1:8081` |
| **Remote Failover** | Remote selected when local unavailable | ✅ PASS | `Selected REMOTE instance: server3:8081` |
| **Recovery Detection** | Background monitor restores healthy | ✅ PASS | `Instance server1:8081 is now healthy` |

### Running the Tests

```bash
# Run all resilience tests
./scripts/test-resilience.sh all

# Run specific test
./scripts/test-resilience.sh passive-health
./scripts/test-resilience.sh circuit-breaker
./scripts/test-resilience.sh load-balancing
./scripts/test-resilience.sh health-monitor

# Check logs for passive health events
docker logs rdp-server1 | grep -E "PASSIVE|FAILOVER|UNHEALTHY|RECOVERY"
```
