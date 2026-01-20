# Performance Testing Guide

This guide covers how to run JMeter performance tests for the RDP MSA POC.

## Overview

The performance test suite uses Apache JMeter integrated with Maven to test:
1. **Happy Path** - Normal operation at 50 requests/second
2. **Local Failure** - Passive health pattern with local instance failure
3. **Remote Failure** - Circuit breaker behavior when all instances fail

## Prerequisites

- Java 21+
- Maven 3.8+
- Docker environment running (all services deployed)
- Services healthy and registered with Eureka

## Quick Start

```bash
# Run all tests (default: happy path at 50 rps for 60 seconds)
cd performance-tests
mvn verify

# Run specific test profile
mvn verify -Phappy-path
mvn verify -Plocal-failure
mvn verify -Premote-failure

# Run with custom parameters
mvn verify -Phappy-path -Dthreads=100 -Dtarget.rps=100 -Dduration=120
```

## Test Profiles

### 1. Happy Path Test (`-Phappy-path`)

Tests normal operation with all services healthy.

**Configuration:**
- Threads: 50
- Ramp-up: 10 seconds
- Duration: 60 seconds
- Target RPS: 50

**Expected Results:**
- 100% success rate (HTTP 200)
- All responses have status "PROCESSED"
- Average response time < 200ms

**Command:**
```bash
mvn verify -Phappy-path
```

### 2. Local Failure Test (`-Plocal-failure`)

Tests passive health pattern when local reference-lookup-service fails.

**Setup (before running test):**
```bash
# Stop local reference-lookup-service on Server 1
docker stop server1-reference-lookup-service
```

**Configuration:**
- Threads: 20
- Ramp-up: 5 seconds
- Duration: 60 seconds
- Target RPS: 20

**Expected Results:**
- High success rate (>95%) via retry to remote
- First request may have higher latency (failover)
- Subsequent requests routed directly to remote
- serverId in response shows remote instance

**Command:**
```bash
mvn verify -Plocal-failure
```

**Cleanup (after test):**
```bash
docker start server1-reference-lookup-service
```

### 3. Remote Failure Test (`-Premote-failure`)

Tests circuit breaker behavior when all downstream instances fail.

**Setup (before running test):**
```bash
# Stop ALL reference-lookup-service instances
docker stop server1-reference-lookup-service
docker stop server3-reference-lookup-service
```

**Configuration:**
- Threads: 20
- Ramp-up: 5 seconds
- Duration: 60 seconds
- Target RPS: 20

**Expected Results:**
- Initial failures (retries exhausted)
- Circuit breaker opens after ~10 failures (50% threshold)
- Fast-fail responses (~50-100ms) when circuit open
- HTTP 503 or fallback response when circuit open

**Command:**
```bash
mvn verify -Premote-failure
```

**Cleanup (after test):**
```bash
docker start server1-reference-lookup-service
docker start server3-reference-lookup-service
```

## Configuration Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `target.host` | localhost | Target host for trade-receiver-service |
| `target.port` | 8082 | Target port |
| `threads` | 50 | Number of concurrent threads |
| `rampup` | 10 | Ramp-up time in seconds |
| `duration` | 60 | Test duration in seconds |
| `target.rps` | 50 | Target requests per second |

### Override Examples

```bash
# Test against different host
mvn verify -Phappy-path -Dtarget.host=192.168.1.100

# Higher load test (100 rps)
mvn verify -Phappy-path -Dthreads=100 -Dtarget.rps=100

# Longer duration test (5 minutes)
mvn verify -Phappy-path -Dduration=300

# Stress test profile (built-in)
mvn verify -Pstress
```

## Reports

After test execution, reports are generated in:

```
performance-tests/target/jmeter/
├── results/          # Raw JTL result files
└── reports/          # HTML dashboard reports
    └── index.html    # Open in browser
```

### Viewing Reports

```bash
# Windows
start performance-tests\target\jmeter\reports\index.html

# Linux/Mac
open performance-tests/target/jmeter/reports/index.html
```

## Key Metrics to Monitor

| Metric | Happy Path Target | With Failures |
|--------|-------------------|---------------|
| Throughput | 50 req/sec | 20 req/sec |
| Avg Response Time | < 200ms | < 500ms (with retry) |
| 95th Percentile | < 500ms | < 1000ms |
| Error Rate | 0% | < 5% (local failure) |
| Circuit Breaker Fast-Fail | N/A | < 100ms |

## Troubleshooting

### Test fails to start
```
Error: Could not connect to target host
```
**Solution:** Ensure Docker services are running:
```bash
docker-compose -f docker/server1/docker-compose.yml ps
```

### Low throughput achieved
```
Actual RPS: 30, Target RPS: 50
```
**Solution:**
- Increase thread count: `-Dthreads=100`
- Check service resource limits in docker-compose

### High error rate in happy path
```
Error rate: 15% (expected 0%)
```
**Solution:**
- Check Eureka registration: `http://localhost:8088`
- Verify all services healthy
- Check service logs for errors

### JMeter out of memory
```
java.lang.OutOfMemoryError: Java heap space
```
**Solution:** Increase JMeter heap in pom.xml:
```xml
<configuration>
    <jMeterProcessJVMSettings>
        <xms>1024</xms>
        <xmx>2048</xmx>
    </jMeterProcessJVMSettings>
</configuration>
```

## Running with JMeter GUI (for debugging)

If you need to modify test plans or debug:

1. Download Apache JMeter from https://jmeter.apache.org/download_jmeter.cgi
2. Open the .jmx files in `performance-tests/src/test/jmeter/`
3. Modify and save
4. Run via Maven as usual

## CI/CD Integration

Add to your CI pipeline:

```yaml
# GitHub Actions example
- name: Run Performance Tests
  run: |
    mvn verify -pl performance-tests -Phappy-path -Dduration=30

- name: Upload JMeter Reports
  uses: actions/upload-artifact@v3
  with:
    name: jmeter-reports
    path: performance-tests/target/jmeter/reports/
```

## Test Scenarios Summary

| Scenario | Services Down | Expected Behavior |
|----------|---------------|-------------------|
| Happy Path | None | All requests succeed |
| Local Failure | Local refdata | Retry to remote, ~100ms added latency |
| Remote Failure | All refdata | Circuit breaker opens, fast-fail |

## Architecture Reference

```
Trade Receiver (8082)
    └── calls → Reference Lookup (8081)
                    ├── Local instance (Server 1)
                    └── Remote instance (Server 3)
```

When local fails:
```
Request → Local (FAIL) → Mark Unhealthy → Retry → Remote (SUCCESS)
```

When all fail:
```
Request → Local (FAIL) → Remote (FAIL) → All retries exhausted
    → Circuit Breaker OPEN → Fast-fail subsequent requests
```
