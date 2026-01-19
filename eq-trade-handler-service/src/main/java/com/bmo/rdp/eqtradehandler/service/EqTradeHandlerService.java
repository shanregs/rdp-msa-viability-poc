package com.bmo.rdp.eqtradehandler.service;

import com.bmo.rdp.common.dto.EligibilityRequest;
import com.bmo.rdp.common.dto.EligibilityResponse;
import com.bmo.rdp.common.dto.ReferenceData;
import com.bmo.rdp.eqtradehandler.dto.RegulatoryRequest;
import com.bmo.rdp.eqtradehandler.dto.RegulatoryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for regulatory processing of equity trades.
 * Uses ResilientServiceClient for calls to Reference Lookup and Check Eligible services
 * with retry and circuit breaker support.
 */
@Service
public class EqTradeHandlerService {

    private static final Logger log = LoggerFactory.getLogger(EqTradeHandlerService.class);

    private final ResilientServiceClient resilientServiceClient;

    // Cache of processed regulatory requests
    private final Map<String, RegulatoryResponse> processingCache = new ConcurrentHashMap<>();

    // Regulatory thresholds
    private static final BigDecimal LARGE_TRADE_THRESHOLD = new BigDecimal("1000000");
    private static final Set<String> RESTRICTED_INSTRUMENTS = Set.of("RESTRICTED_01", "RESTRICTED_02");

    @Value("${server.id:unknown}")
    private String serverId;

    public EqTradeHandlerService(ResilientServiceClient resilientServiceClient) {
        this.resilientServiceClient = resilientServiceClient;
    }

    /**
     * Process a regulatory request.
     *
     * The ResilientServiceClient handles retry and circuit breaker logic for downstream calls.
     * If all retries fail, the circuit breaker fallback returns a safe response.
     */
    public RegulatoryResponse processRegulatory(RegulatoryRequest request) {
        log.info("Processing regulatory request for trade: {} on {}", request.tradeId(), serverId);

        try {
            List<String> findings = new ArrayList<>();

            // Step 1: Get reference data (with retry/circuit breaker)
            log.debug("Calling reference lookup for instrument: {}", request.instrument());
            ReferenceData refData = resilientServiceClient.getInstrumentData(request.instrument());
            log.debug("Got reference data for {}: {}", request.instrument(),
                    refData != null ? refData.serverId() : "null");

            // Step 2: Check eligibility (with retry/circuit breaker)
            EligibilityRequest eligibilityRequest = new EligibilityRequest(
                    request.tradeId(),
                    request.tradeType(),
                    request.counterparty(),
                    request.quantity().multiply(request.price()),
                    request.currency()
            );
            log.debug("Calling eligibility check for trade: {}", request.tradeId());
            EligibilityResponse eligibility = resilientServiceClient.checkEligibility(eligibilityRequest);
            log.debug("Eligibility check for {}: {} (from {})",
                    request.tradeId(), eligibility.eligible(), eligibility.serverId());

            if (!eligibility.eligible()) {
                findings.addAll(eligibility.reasons());
            }

            // Step 3: Apply regulatory rules
            BigDecimal notionalValue = request.quantity().multiply(request.price());

            // Rule 1: Large trade reporting
            if (notionalValue.compareTo(LARGE_TRADE_THRESHOLD) > 0) {
                findings.add("Trade requires large trade reporting (value > " + LARGE_TRADE_THRESHOLD + ")");
            }

            // Rule 2: Restricted instruments
            if (RESTRICTED_INSTRUMENTS.contains(request.instrument())) {
                findings.add("Trading restricted instrument: " + request.instrument());
            }

            // Rule 3: Settlement date validation
            if (request.settlementDate() != null && request.tradeDate() != null) {
                long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(
                        request.tradeDate(), request.settlementDate());
                if (daysBetween < 0) {
                    findings.add("Settlement date cannot be before trade date");
                } else if (daysBetween > 5) {
                    findings.add("Settlement date exceeds T+5 standard");
                }
            }

            // Step 4: Create response
            RegulatoryResponse response;
            if (findings.isEmpty()) {
                response = RegulatoryResponse.compliant(
                        request.tradeId(),
                        request.regulationType(),
                        refData,
                        serverId
                );
            } else {
                response = RegulatoryResponse.nonCompliant(
                        request.tradeId(),
                        request.regulationType(),
                        findings,
                        serverId
                );
            }

            processingCache.put(request.tradeId(), response);
            return response;

        } catch (Exception e) {
            // This catch block handles unexpected errors not handled by Resilience4j
            // Circuit breaker fallbacks should prevent most exceptions from reaching here
            log.error("Unexpected error processing regulatory request {}: {}", request.tradeId(), e.getMessage(), e);
            RegulatoryResponse errorResponse = RegulatoryResponse.error(
                    request.tradeId(),
                    "Unexpected error: " + e.getMessage(),
                    serverId
            );
            processingCache.put(request.tradeId(), errorResponse);
            return errorResponse;
        }
    }

    public Optional<RegulatoryResponse> getProcessingStatus(String tradeId) {
        return Optional.ofNullable(processingCache.get(tradeId));
    }
}
