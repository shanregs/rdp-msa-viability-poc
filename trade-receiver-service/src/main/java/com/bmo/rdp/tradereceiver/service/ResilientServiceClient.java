package com.bmo.rdp.tradereceiver.service;

import com.bmo.rdp.common.dto.EligibilityRequest;
import com.bmo.rdp.common.dto.EligibilityResponse;
import com.bmo.rdp.common.dto.ReferenceData;
import com.bmo.rdp.tradereceiver.client.CheckEligibleClient;
import com.bmo.rdp.tradereceiver.client.ReferenceLookupClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resilient wrapper for service clients.
 *
 * Architecture:
 * - Public methods have @CircuitBreaker with fallback (outer layer)
 * - They call internal methods with @Retry via self-injection (inner layer)
 * - Self-injection ensures Spring AOP can intercept internal method calls
 * - This ensures: Retry attempts → If all fail → CircuitBreaker records failure → Fallback
 *
 * The retry configuration uses passive health pattern:
 * - 0ms wait-duration for immediate failover to next instance
 * - PassiveHealthRetryEventListener marks failed instances as unhealthy
 * - Load balancer skips already-tried instances on retry
 */
@Service
public class ResilientServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientServiceClient.class);

    private final ReferenceLookupClient referenceLookupClient;
    private final CheckEligibleClient checkEligibleClient;

    @Value("${server.id:unknown}")
    private String serverId;

    // Self-injection to ensure AOP proxy is used for internal method calls
    @Lazy
    @Autowired
    private ResilientServiceClient self;

    public ResilientServiceClient(ReferenceLookupClient referenceLookupClient,
                                  CheckEligibleClient checkEligibleClient) {
        this.referenceLookupClient = referenceLookupClient;
        this.checkEligibleClient = checkEligibleClient;
    }

    /**
     * Get instrument reference data with circuit breaker and fallback.
     * Calls the retry-protected method via self-injection for AOP interception.
     */
    @CircuitBreaker(name = "referenceLookup", fallbackMethod = "getInstrumentDataFallback")
    public ReferenceData getInstrumentData(String instrument) {
        // Use self-injection to ensure @Retry is intercepted by AOP
        return self.getInstrumentDataWithRetry(instrument);
    }

    /**
     * Method with retry only (no fallback).
     * Exceptions propagate to the outer CircuitBreaker.
     */
    @Retry(name = "referenceLookup")
    public ReferenceData getInstrumentDataWithRetry(String instrument) {
        log.debug("Calling reference lookup for instrument: {}", instrument);
        return referenceLookupClient.getInstrumentData(instrument);
    }

    /**
     * Check trade eligibility with circuit breaker and fallback.
     * Calls the retry-protected method via self-injection for AOP interception.
     */
    @CircuitBreaker(name = "checkEligible", fallbackMethod = "checkEligibilityFallback")
    public EligibilityResponse checkEligibility(EligibilityRequest request) {
        // Use self-injection to ensure @Retry is intercepted by AOP
        return self.checkEligibilityWithRetry(request);
    }

    /**
     * Method with retry only (no fallback).
     * Exceptions propagate to the outer CircuitBreaker.
     */
    @Retry(name = "checkEligible")
    public EligibilityResponse checkEligibilityWithRetry(EligibilityRequest request) {
        log.debug("Calling eligibility check for trade: {}", request.tradeId());
        return checkEligibleClient.checkEligibility(request);
    }

    // Fallback methods - called when circuit breaker is open or all retries exhausted

    private ReferenceData getInstrumentDataFallback(String instrument, Throwable t) {
        log.warn("Fallback triggered for reference lookup: {} - {}", instrument, t.getMessage());
        return ReferenceData.of("FALLBACK", "INSTRUMENT", instrument, "Fallback data - service unavailable", serverId);
    }

    private EligibilityResponse checkEligibilityFallback(EligibilityRequest request, Throwable t) {
        log.warn("Fallback triggered for eligibility check: {} - {}", request.tradeId(), t.getMessage());
        // In fallback, we reject the trade for safety
        return EligibilityResponse.ineligible(
                request.tradeId(),
                List.of("Eligibility service unavailable - trade rejected for safety"),
                serverId
        );
    }
}
