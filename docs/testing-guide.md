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
