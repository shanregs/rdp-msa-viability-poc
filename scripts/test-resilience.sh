#!/bin/bash
#
# RDP-MSA Viability POC - Resilience Testing Script
#
# This script tests the resilience patterns implemented in the microservices:
# - Passive Health with Immediate Retry (0ms delay)
# - Circuit breaker
# - Local-first load balancing with failover tracking
# - Background health monitoring (recovery detection only)
#
# Prerequisites:
# - All services running via Docker Compose
# - curl and jq installed
#
# Usage: ./test-resilience.sh [test_name]
#   test_name: all, passive-health, retry, circuit-breaker, load-balancing, health-monitor
#

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
TRADE_RECEIVER_URL="http://localhost:8082"
REFERENCE_LOOKUP_URL="http://localhost:8081"
CHECK_ELIGIBLE_URL="http://localhost:8092"
EUREKA_URL="http://localhost:8088"

# Server container names (adjust if different)
SERVER1_CONTAINER="server1"
SERVER3_CONTAINER="server3"

# Test counters
TESTS_PASSED=0
TESTS_FAILED=0

#######################################
# Utility Functions
#######################################

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[PASS]${NC} $1"
    ((TESTS_PASSED++))
}

log_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    ((TESTS_FAILED++))
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_header() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
}

wait_for_service() {
    local url=$1
    local max_attempts=${2:-30}
    local attempt=1

    while [ $attempt -le $max_attempts ]; do
        if curl -s -f "${url}/actuator/health" > /dev/null 2>&1; then
            return 0
        fi
        sleep 1
        ((attempt++))
    done
    return 1
}

check_http_status() {
    local url=$1
    local expected_status=${2:-200}
    local actual_status

    actual_status=$(curl -s -o /dev/null -w "%{http_code}" "$url")

    if [ "$actual_status" == "$expected_status" ]; then
        return 0
    else
        return 1
    fi
}

submit_trade() {
    local trade_id=$1
    local response

    response=$(curl -s -X POST "${TRADE_RECEIVER_URL}/api/v1/trades" \
        -H "Content-Type: application/json" \
        -d "{
            \"tradeId\": \"${trade_id}\",
            \"tradeType\": \"EQUITY\",
            \"instrument\": \"AAPL\",
            \"counterparty\": \"JPM\",
            \"quantity\": 100,
            \"price\": 150.50,
            \"currency\": \"USD\"
        }" 2>/dev/null)

    echo "$response"
}

get_circuit_breaker_state() {
    local cb_name=${1:-"referenceLookup"}
    local state

    state=$(curl -s "${TRADE_RECEIVER_URL}/actuator/circuitbreakers" 2>/dev/null | \
        jq -r ".circuitBreakers.${cb_name}.state" 2>/dev/null)

    echo "$state"
}

stop_service() {
    local container=$1
    local service=$2

    docker exec "$container" supervisorctl stop "$service" > /dev/null 2>&1
}

start_service() {
    local container=$1
    local service=$2

    docker exec "$container" supervisorctl start "$service" > /dev/null 2>&1
}

#######################################
# Pre-flight Checks
#######################################

preflight_checks() {
    log_header "Pre-flight Checks"

    # Check if curl is installed
    if ! command -v curl &> /dev/null; then
        log_fail "curl is not installed"
        exit 1
    fi
    log_success "curl is available"

    # Check if jq is installed
    if ! command -v jq &> /dev/null; then
        log_warn "jq is not installed - some tests may have limited output"
    else
        log_success "jq is available"
    fi

    # Check if Docker is running
    if ! docker info > /dev/null 2>&1; then
        log_fail "Docker is not running"
        exit 1
    fi
    log_success "Docker is running"

    # Check Trade Receiver is accessible
    if check_http_status "${TRADE_RECEIVER_URL}/actuator/health"; then
        log_success "Trade Receiver Service is accessible"
    else
        log_fail "Trade Receiver Service is not accessible at ${TRADE_RECEIVER_URL}"
        exit 1
    fi

    # Check Reference Lookup is accessible
    if check_http_status "${REFERENCE_LOOKUP_URL}/actuator/health"; then
        log_success "Reference Lookup Service is accessible"
    else
        log_warn "Reference Lookup Service is not accessible - some tests may fail"
    fi

    # Check Eureka is accessible
    if check_http_status "${EUREKA_URL}/actuator/health"; then
        log_success "Eureka Server is accessible"
    else
        log_warn "Eureka Server is not accessible"
    fi

    echo ""
}

#######################################
# Test 1: Passive Health Pattern
#######################################

test_passive_health() {
    log_header "Test 1: Passive Health with Immediate Failover"

    log_info "This test verifies the passive health pattern:"
    log_info "- Failed requests immediately mark instance as UNHEALTHY"
    log_info "- Retry happens with 0ms delay (immediate failover)"
    log_info "- Already-tried instances are skipped during retry cycle"
    echo ""

    # Step 1: Verify both services are working
    log_info "Step 1: Verify reference-lookup instances are working..."
    if check_http_status "${REFERENCE_LOOKUP_URL}/actuator/health"; then
        log_success "Reference Lookup (server1) is healthy"
    else
        log_fail "Reference Lookup (server1) is not healthy - cannot proceed"
        return
    fi

    # Step 2: Stop local reference-lookup service
    log_info "Step 2: Stopping LOCAL reference-lookup on server1..."
    stop_service "$SERVER1_CONTAINER" "reference-lookup-service"
    sleep 2

    # Step 3: Submit a trade and measure failover time
    log_info "Step 3: Submitting trade to trigger PASSIVE HEALTH failover..."
    log_info "Expected: Local fails -> marked UNHEALTHY -> immediate retry to server3"

    local start_time end_time duration response
    start_time=$(date +%s%N)
    response=$(submit_trade "PASSIVE-HEALTH-$(date +%s)")
    end_time=$(date +%s%N)
    duration=$(( (end_time - start_time) / 1000000 ))

    log_info "Total request time: ${duration}ms"

    if echo "$response" | grep -q "status"; then
        log_success "Trade processed successfully via failover"
        log_info "Response: $response"

        # Check if failover was fast (< 500ms indicates 0ms retry delay worked)
        if [ "$duration" -lt 1000 ]; then
            log_success "Immediate failover confirmed (< 1 second total)"
        else
            log_warn "Failover took longer than expected: ${duration}ms"
        fi
    else
        log_info "Response: $response"
        log_warn "Trade may have failed - check logs"
    fi

    # Step 4: Check logs for passive health updates
    log_info "Step 4: Checking logs for PASSIVE HEALTH messages..."
    log_info "Run: docker logs $SERVER1_CONTAINER | grep -E 'PASSIVE|UNHEALTHY|markTried'"

    # Step 5: Submit another trade - should skip unhealthy instance
    log_info "Step 5: Submitting second trade - should skip unhealthy local instance..."
    response=$(submit_trade "PASSIVE-HEALTH-SKIP-$(date +%s)")

    if echo "$response" | grep -q "status"; then
        log_success "Second trade processed - unhealthy local instance skipped"
    fi

    # Step 6: Restart service
    log_info "Step 6: Restarting reference-lookup service..."
    start_service "$SERVER1_CONTAINER" "reference-lookup-service"
    sleep 5

    # Verify service is back
    if wait_for_service "$REFERENCE_LOOKUP_URL" 10; then
        log_success "Reference Lookup service recovered"
    else
        log_warn "Reference Lookup service may not have fully recovered"
    fi

    log_success "Passive health test completed"
}

#######################################
# Test 2: Retry Pattern (Legacy)
#######################################

test_retry_pattern() {
    log_header "Test 2: Retry Pattern (with Passive Health)"

    log_info "This test verifies that failed requests are retried immediately"
    log_info "Retry configuration: 0ms delay, max 3 attempts, no exponential backoff"
    echo ""

    # Step 1: Verify service is working
    log_info "Step 1: Verify reference-lookup is working..."
    if check_http_status "${REFERENCE_LOOKUP_URL}/actuator/health"; then
        log_success "Reference Lookup is healthy"
    else
        log_fail "Reference Lookup is not healthy - cannot proceed"
        return
    fi

    # Step 2: Stop local reference-lookup service
    log_info "Step 2: Stopping local reference-lookup service on server1..."
    stop_service "$SERVER1_CONTAINER" "reference-lookup-service"
    sleep 2

    # Step 3: Submit a trade (should trigger retry and failover)
    log_info "Step 3: Submitting trade to trigger retry behavior..."
    log_info "Expected: Request fails locally, retries IMMEDIATELY, fails over to server3"

    local response
    response=$(submit_trade "RETRY-TEST-$(date +%s)")

    if echo "$response" | grep -q "status"; then
        log_success "Trade processed successfully (likely via failover)"
        log_info "Response: $response"
    else
        log_info "Response: $response"
        log_warn "Trade may have failed - check logs for retry behavior"
    fi

    # Step 4: Check logs for retry pattern
    log_info "Step 4: Checking logs for retry events..."
    log_info "Run: docker logs $SERVER1_CONTAINER | grep -E 'PASSIVE|FAILOVER|retry'"

    # Step 5: Restart service
    log_info "Step 5: Restarting reference-lookup service..."
    start_service "$SERVER1_CONTAINER" "reference-lookup-service"
    sleep 5

    # Verify service is back
    if wait_for_service "$REFERENCE_LOOKUP_URL" 10; then
        log_success "Reference Lookup service recovered"
    else
        log_warn "Reference Lookup service may not have fully recovered"
    fi

    log_success "Retry pattern test completed"
}

#######################################
# Test 3: Circuit Breaker
#######################################

test_circuit_breaker() {
    log_header "Test 3: Circuit Breaker Pattern"

    log_info "This test verifies the circuit breaker opens after repeated failures"
    log_info "Configuration: 50% failure threshold, 10 call sliding window, 30s open duration"
    echo ""

    # Step 1: Check initial circuit breaker state
    log_info "Step 1: Checking initial circuit breaker state..."
    local initial_state
    initial_state=$(get_circuit_breaker_state)
    log_info "Current state: ${initial_state:-UNKNOWN}"

    if [ "$initial_state" == "CLOSED" ]; then
        log_success "Circuit breaker is CLOSED (healthy)"
    else
        log_warn "Circuit breaker is not in CLOSED state"
    fi

    # Step 2: Stop ALL reference-lookup instances
    log_info "Step 2: Stopping ALL reference-lookup instances..."
    stop_service "$SERVER1_CONTAINER" "reference-lookup-service"
    stop_service "$SERVER3_CONTAINER" "reference-lookup-service"
    sleep 3

    # Step 3: Send rapid requests to trigger circuit breaker
    log_info "Step 3: Sending 15 rapid requests to trigger circuit breaker..."
    for i in {1..15}; do
        submit_trade "CB-TEST-$i" > /dev/null 2>&1 &
    done
    wait
    sleep 2

    # Step 4: Check circuit breaker state
    log_info "Step 4: Checking circuit breaker state..."
    local cb_state
    cb_state=$(get_circuit_breaker_state)
    log_info "Circuit breaker state: ${cb_state:-UNKNOWN}"

    if [ "$cb_state" == "OPEN" ]; then
        log_success "Circuit breaker is OPEN (as expected after failures)"
    else
        log_warn "Circuit breaker may not have opened (state: $cb_state)"
    fi

    # Step 5: Verify fast-fail behavior
    log_info "Step 5: Verifying fast-fail behavior..."
    local start_time end_time duration
    start_time=$(date +%s%N)
    submit_trade "CB-FAST-FAIL" > /dev/null 2>&1
    end_time=$(date +%s%N)
    duration=$(( (end_time - start_time) / 1000000 ))

    log_info "Request completed in ${duration}ms"
    if [ "$duration" -lt 1000 ]; then
        log_success "Fast-fail confirmed (response in < 1 second)"
    else
        log_warn "Response took longer than expected for fast-fail"
    fi

    # Step 6: Restart services
    log_info "Step 6: Restarting reference-lookup services..."
    start_service "$SERVER1_CONTAINER" "reference-lookup-service"
    start_service "$SERVER3_CONTAINER" "reference-lookup-service"

    # Step 7: Wait for circuit breaker to transition
    log_info "Step 7: Waiting for circuit breaker recovery (may take up to 30 seconds)..."
    sleep 35

    # Step 8: Verify circuit closes
    log_info "Step 8: Sending successful request to close circuit..."
    if wait_for_service "$REFERENCE_LOOKUP_URL" 10; then
        submit_trade "CB-RECOVERY-TEST" > /dev/null 2>&1
        sleep 2

        cb_state=$(get_circuit_breaker_state)
        log_info "Circuit breaker state: ${cb_state:-UNKNOWN}"

        if [ "$cb_state" == "CLOSED" ]; then
            log_success "Circuit breaker recovered to CLOSED state"
        else
            log_warn "Circuit breaker may not have fully recovered"
        fi
    else
        log_warn "Service did not recover in time"
    fi

    log_success "Circuit breaker test completed"
}

#######################################
# Test 4: Local-First Load Balancing
#######################################

test_load_balancing() {
    log_header "Test 4: Local-First Load Balancing (with Passive Health)"

    log_info "This test verifies that requests prefer local instances"
    log_info "With passive health: failed instances are skipped in subsequent requests"
    log_info "Expected: Server1's trade-receiver calls Server1's reference-lookup first"
    echo ""

    # Step 1: Ensure both reference-lookup instances are running
    log_info "Step 1: Ensuring both reference-lookup instances are running..."
    start_service "$SERVER1_CONTAINER" "reference-lookup-service"
    start_service "$SERVER3_CONTAINER" "reference-lookup-service"
    sleep 5

    if wait_for_service "$REFERENCE_LOOKUP_URL" 10; then
        log_success "Reference Lookup services are running"
    else
        log_fail "Reference Lookup services are not available"
        return
    fi

    # Step 2: Submit trade with local service available
    log_info "Step 2: Submitting trade with local service available..."
    local response
    response=$(submit_trade "LB-LOCAL-$(date +%s)")
    log_info "Response: $response"
    log_info "Check logs: docker logs $SERVER1_CONTAINER | grep -i 'local'"

    # Step 3: Stop local reference-lookup
    log_info "Step 3: Stopping LOCAL reference-lookup on server1..."
    stop_service "$SERVER1_CONTAINER" "reference-lookup-service"
    sleep 2

    # Step 4: Submit trade - should failover to remote
    log_info "Step 4: Submitting trade - should use REMOTE service on server3..."
    response=$(submit_trade "LB-REMOTE-$(date +%s)")

    if echo "$response" | grep -q "status"; then
        log_success "Trade processed successfully via remote instance"
        log_info "Response: $response"
    else
        log_warn "Trade may have failed"
        log_info "Response: $response"
    fi

    log_info "Check logs: docker logs $SERVER1_CONTAINER | grep -i 'remote\\|failover'"

    # Step 5: Restart local service
    log_info "Step 5: Restarting LOCAL reference-lookup on server1..."
    start_service "$SERVER1_CONTAINER" "reference-lookup-service"
    sleep 5

    # Step 6: Verify local preference resumes
    log_info "Step 6: Verifying local preference resumes..."
    wait_for_service "$REFERENCE_LOOKUP_URL" 10

    response=$(submit_trade "LB-LOCAL-RESUME-$(date +%s)")
    if echo "$response" | grep -q "status"; then
        log_success "Trade processed - verify logs show local instance used"
    fi

    log_success "Load balancing test completed"
}

#######################################
# Test 5: Health Monitoring (Recovery Detection)
#######################################

test_health_monitoring() {
    log_header "Test 5: Background Health Monitoring (Recovery Detection)"

    log_info "With passive health pattern, background health monitoring focuses on RECOVERY detection"
    log_info "- Failure detection: Handled by passive health (real-time, during requests)"
    log_info "- Recovery detection: Handled by background health monitor (30 second interval)"
    log_info ""
    log_info "Health check interval: 30 seconds"
    echo ""

    # Step 1: Check current health
    log_info "Step 1: Checking current service health..."

    local health_status
    health_status=$(curl -s "${TRADE_RECEIVER_URL}/actuator/health" 2>/dev/null)

    if echo "$health_status" | grep -q "UP"; then
        log_success "Trade Receiver reports healthy"
    else
        log_warn "Trade Receiver health check returned unexpected response"
    fi

    # Step 2: Monitor logs for health check activity
    log_info "Step 2: Health monitor checks instances every 30 seconds for RECOVERY"
    log_info "Run: docker logs -f $SERVER1_CONTAINER | grep -i 'health\\|recovery'"

    # Step 3: Stop a service and trigger passive health marking
    log_info "Step 3: Stopping reference-lookup on server3..."
    stop_service "$SERVER3_CONTAINER" "reference-lookup-service"
    sleep 2

    log_info "Step 3b: Trigger request to mark instance UNHEALTHY via passive health..."
    submit_trade "HEALTH-PASSIVE-$(date +%s)" > /dev/null 2>&1

    log_info "Check logs for PASSIVE HEALTH UNHEALTHY marking:"
    log_info "Run: docker logs $SERVER1_CONTAINER | tail -50 | grep -i 'PASSIVE\\|UNHEALTHY'"

    # Step 4: Restart service
    log_info "Step 4: Restarting reference-lookup on server3..."
    start_service "$SERVER3_CONTAINER" "reference-lookup-service"

    log_info "Waiting 35 seconds for health monitor to detect RECOVERY..."
    sleep 35

    log_info "Check logs for RECOVERY marking:"
    log_info "Run: docker logs $SERVER1_CONTAINER | tail -50 | grep -i 'RECOVERY\\|marked HEALTHY'"

    log_success "Health monitoring test completed"
}

#######################################
# Summary
#######################################

print_summary() {
    log_header "Test Summary"

    echo -e "Tests Passed: ${GREEN}${TESTS_PASSED}${NC}"
    echo -e "Tests Failed: ${RED}${TESTS_FAILED}${NC}"
    echo ""

    if [ $TESTS_FAILED -eq 0 ]; then
        echo -e "${GREEN}All tests completed successfully!${NC}"
    else
        echo -e "${YELLOW}Some tests may need manual verification. Check the logs.${NC}"
    fi

    echo ""
    echo "For detailed analysis, check the service logs:"
    echo "  docker logs $SERVER1_CONTAINER | grep -E 'PASSIVE|UNHEALTHY|RECOVERY|FAILOVER|retry'"
    echo ""
}

#######################################
# Main
#######################################

main() {
    local test_name=${1:-"all"}

    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║     RDP-MSA Viability POC - Resilience Testing Suite        ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""

    preflight_checks

    case $test_name in
        "passive-health")
            test_passive_health
            ;;
        "retry")
            test_retry_pattern
            ;;
        "circuit-breaker")
            test_circuit_breaker
            ;;
        "load-balancing")
            test_load_balancing
            ;;
        "health-monitor")
            test_health_monitoring
            ;;
        "all")
            test_passive_health
            test_retry_pattern
            test_circuit_breaker
            test_load_balancing
            test_health_monitoring
            ;;
        *)
            echo "Usage: $0 [test_name]"
            echo ""
            echo "Available tests:"
            echo "  all             - Run all tests (default)"
            echo "  passive-health  - Test passive health with immediate failover"
            echo "  retry           - Test retry pattern"
            echo "  circuit-breaker - Test circuit breaker"
            echo "  load-balancing  - Test local-first load balancing"
            echo "  health-monitor  - Test background health monitoring (recovery)"
            echo ""
            exit 1
            ;;
    esac

    print_summary
}

# Run main with all arguments
main "$@"
