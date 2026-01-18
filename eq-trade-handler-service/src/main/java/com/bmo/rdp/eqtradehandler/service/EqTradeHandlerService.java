package com.bmo.rdp.eqtradehandler.service;

import com.bmo.rdp.common.dto.EligibilityRequest;
import com.bmo.rdp.common.dto.EligibilityResponse;
import com.bmo.rdp.common.dto.ReferenceData;
import com.bmo.rdp.eqtradehandler.client.CheckEligibleClient;
import com.bmo.rdp.eqtradehandler.client.ReferenceLookupClient;
import com.bmo.rdp.eqtradehandler.dto.RegulatoryRequest;
import com.bmo.rdp.eqtradehandler.dto.RegulatoryResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for regulatory processing of equity trades.
 */
@Service
public class EqTradeHandlerService {

    private static final Logger log = LoggerFactory.getLogger(EqTradeHandlerService.class);

    private final ReferenceLookupClient referenceLookupClient;
    private final CheckEligibleClient checkEligibleClient;

    // Cache of processed regulatory requests
    private final Map<String, RegulatoryResponse> processingCache = new ConcurrentHashMap<>();

    // Regulatory thresholds
    private static final BigDecimal LARGE_TRADE_THRESHOLD = new BigDecimal("1000000");
    private static final Set<String> RESTRICTED_INSTRUMENTS = Set.of("RESTRICTED_01", "RESTRICTED_02");

    @Value("${server.id:unknown}")
    private String serverId;

    public EqTradeHandlerService(ReferenceLookupClient referenceLookupClient,
                                 CheckEligibleClient checkEligibleClient) {
        this.referenceLookupClient = referenceLookupClient;
        this.checkEligibleClient = checkEligibleClient;
    }

    @CircuitBreaker(name = "regulatoryProcessing", fallbackMethod = "processRegulatoryFallback")
    @Retry(name = "regulatoryProcessing")
    public RegulatoryResponse processRegulatory(RegulatoryRequest request) {
        log.info("Processing regulatory request for trade: {} on {}", request.tradeId(), serverId);

        try {
            List<String> findings = new ArrayList<>();

            // Step 1: Get reference data
            ReferenceData refData = getInstrumentData(request.instrument());
            log.debug("Got reference data for {}: {}", request.instrument(),
                    refData != null ? refData.serverId() : "null");

            // Step 2: Check eligibility
            EligibilityRequest eligibilityRequest = new EligibilityRequest(
                    request.tradeId(),
                    request.tradeType(),
                    request.counterparty(),
                    request.quantity().multiply(request.price()),
                    request.currency()
            );
            EligibilityResponse eligibility = checkEligibility(eligibilityRequest);
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
            log.error("Error processing regulatory request {}: {}", request.tradeId(), e.getMessage(), e);
            RegulatoryResponse errorResponse = RegulatoryResponse.error(
                    request.tradeId(),
                    "Error processing regulatory request: " + e.getMessage(),
                    serverId
            );
            processingCache.put(request.tradeId(), errorResponse);
            return errorResponse;
        }
    }

    @CircuitBreaker(name = "referenceLookup", fallbackMethod = "getInstrumentDataFallback")
    @Retry(name = "referenceLookup")
    private ReferenceData getInstrumentData(String instrument) {
        log.debug("Calling reference lookup for instrument: {}", instrument);
        return referenceLookupClient.getInstrumentData(instrument);
    }

    @CircuitBreaker(name = "checkEligible", fallbackMethod = "checkEligibilityFallback")
    @Retry(name = "checkEligible")
    private EligibilityResponse checkEligibility(EligibilityRequest request) {
        log.debug("Calling eligibility check for trade: {}", request.tradeId());
        return checkEligibleClient.checkEligibility(request);
    }

    public Optional<RegulatoryResponse> getProcessingStatus(String tradeId) {
        return Optional.ofNullable(processingCache.get(tradeId));
    }

    // Fallback methods

    private RegulatoryResponse processRegulatoryFallback(RegulatoryRequest request, Throwable t) {
        log.warn("Fallback triggered for regulatory processing: {} - {}", request.tradeId(), t.getMessage());
        return RegulatoryResponse.error(
                request.tradeId(),
                "Service temporarily unavailable. Please retry later.",
                serverId
        );
    }

    private ReferenceData getInstrumentDataFallback(String instrument, Throwable t) {
        log.warn("Fallback triggered for reference lookup: {} - {}", instrument, t.getMessage());
        return ReferenceData.of("FALLBACK", "INSTRUMENT", instrument, "Fallback data", serverId);
    }

    private EligibilityResponse checkEligibilityFallback(EligibilityRequest request, Throwable t) {
        log.warn("Fallback triggered for eligibility check: {} - {}", request.tradeId(), t.getMessage());
        return EligibilityResponse.ineligible(
                request.tradeId(),
                List.of("Eligibility service unavailable - trade rejected for safety"),
                serverId
        );
    }
}
