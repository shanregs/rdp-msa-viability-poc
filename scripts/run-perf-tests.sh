#!/bin/bash
# Performance Test Runner for RDP MSA POC
# Usage: ./run-perf-tests.sh [test-type] [options]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Default values
TEST_TYPE="happy-path"
THREADS=50
RAMPUP=10
DURATION=60
TARGET_RPS=50
TARGET_HOST="localhost"
TARGET_PORT="8082"

usage() {
    echo "Usage: $0 [test-type] [options]"
    echo ""
    echo "Test Types:"
    echo "  happy-path     Normal operation test (default)"
    echo "  local-failure  Test passive health pattern with local instance down"
    echo "  remote-failure Test circuit breaker with all instances down"
    echo "  stress         High load test (100 rps)"
    echo "  all            Run all test types sequentially"
    echo ""
    echo "Options:"
    echo "  --threads N      Number of concurrent threads (default: 50)"
    echo "  --rampup N       Ramp-up time in seconds (default: 10)"
    echo "  --duration N     Test duration in seconds (default: 60)"
    echo "  --rps N          Target requests per second (default: 50)"
    echo "  --host HOST      Target host (default: localhost)"
    echo "  --port PORT      Target port (default: 8082)"
    echo "  --help           Show this help message"
    echo ""
    echo "Examples:"
    echo "  $0 happy-path"
    echo "  $0 happy-path --rps 100 --duration 120"
    echo "  $0 local-failure --threads 20"
    echo "  $0 all"
}

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

check_prerequisites() {
    log_info "Checking prerequisites..."

    # Check Maven
    if ! command -v mvn &> /dev/null; then
        log_error "Maven not found. Please install Maven 3.8+"
        exit 1
    fi

    # Check Java
    if ! command -v java &> /dev/null; then
        log_error "Java not found. Please install Java 21+"
        exit 1
    fi

    # Check if trade-receiver is accessible
    if curl -s --connect-timeout 5 "http://${TARGET_HOST}:${TARGET_PORT}/api/v1/trades/health/instance" > /dev/null 2>&1; then
        log_info "Trade Receiver Service is accessible at ${TARGET_HOST}:${TARGET_PORT}"
    else
        log_warn "Cannot reach Trade Receiver Service at ${TARGET_HOST}:${TARGET_PORT}"
        log_warn "Make sure Docker services are running"
    fi
}

run_test() {
    local profile=$1
    log_info "Running ${profile} test..."
    log_info "Configuration: threads=${THREADS}, rps=${TARGET_RPS}, duration=${DURATION}s"

    cd "$PROJECT_DIR/performance-tests"

    mvn verify -P${profile} \
        -Dtarget.host=${TARGET_HOST} \
        -Dtarget.port=${TARGET_PORT} \
        -Dthreads=${THREADS} \
        -Drampup=${RAMPUP} \
        -Dduration=${DURATION} \
        -Dtarget.rps=${TARGET_RPS}

    log_info "Test complete. Reports available at:"
    log_info "  ${PROJECT_DIR}/performance-tests/target/jmeter/reports/index.html"
}

setup_local_failure() {
    log_warn "For local-failure test, you should stop the local reference-lookup-service:"
    log_warn "  docker stop server1-reference-lookup-service"
    echo ""
    read -p "Press Enter when ready to continue, or Ctrl+C to cancel..."
}

setup_remote_failure() {
    log_warn "For remote-failure test, you should stop ALL reference-lookup-service instances:"
    log_warn "  docker stop server1-reference-lookup-service"
    log_warn "  docker stop server3-reference-lookup-service"
    echo ""
    read -p "Press Enter when ready to continue, or Ctrl+C to cancel..."
}

cleanup_failure_test() {
    log_info "Remember to restart stopped services:"
    log_info "  docker start server1-reference-lookup-service"
    log_info "  docker start server3-reference-lookup-service"
}

# Parse arguments
if [[ $# -gt 0 && ! "$1" =~ ^-- ]]; then
    TEST_TYPE=$1
    shift
fi

while [[ $# -gt 0 ]]; do
    case $1 in
        --threads)
            THREADS=$2
            shift 2
            ;;
        --rampup)
            RAMPUP=$2
            shift 2
            ;;
        --duration)
            DURATION=$2
            shift 2
            ;;
        --rps)
            TARGET_RPS=$2
            shift 2
            ;;
        --host)
            TARGET_HOST=$2
            shift 2
            ;;
        --port)
            TARGET_PORT=$2
            shift 2
            ;;
        --help)
            usage
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

# Main execution
echo "=============================================="
echo "  RDP MSA POC Performance Test Runner"
echo "=============================================="
echo ""

check_prerequisites

case $TEST_TYPE in
    happy-path)
        run_test "happy-path"
        ;;
    local-failure)
        setup_local_failure
        THREADS=20
        TARGET_RPS=20
        run_test "local-failure"
        cleanup_failure_test
        ;;
    remote-failure)
        setup_remote_failure
        THREADS=20
        TARGET_RPS=20
        run_test "remote-failure"
        cleanup_failure_test
        ;;
    stress)
        THREADS=100
        TARGET_RPS=100
        DURATION=120
        run_test "stress"
        ;;
    all)
        log_info "Running all tests sequentially..."

        log_info "=== Test 1/3: Happy Path ==="
        run_test "happy-path"

        log_info "=== Test 2/3: Local Failure ==="
        setup_local_failure
        THREADS=20
        TARGET_RPS=20
        run_test "local-failure"

        log_info "=== Test 3/3: Remote Failure ==="
        setup_remote_failure
        run_test "remote-failure"

        cleanup_failure_test
        log_info "All tests complete!"
        ;;
    *)
        log_error "Unknown test type: $TEST_TYPE"
        usage
        exit 1
        ;;
esac

echo ""
log_info "Done!"
