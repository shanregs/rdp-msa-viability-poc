package com.bmo.rdp.tradereceiver.service;

import com.bmo.rdp.common.dto.*;
import com.bmo.rdp.tradereceiver.client.CheckEligibleClient;
import com.bmo.rdp.tradereceiver.client.ReferenceLookupClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for processing incoming trades.
 * Calls Reference Lookup and Check Eligible services with local-first load balancing.
 */
@Service
public class TradeReceiverService {

    private static final Logger log = LoggerFactory.getLogger(TradeReceiverService.class);

    private final ReferenceLookupClient referenceLookupClient;
    private final CheckEligibleClient checkEligibleClient;

    // Cache of processed trades
    private final Map<String, TradeResponse> tradeCache = new ConcurrentHashMap<>();

    @Value("${server.id:unknown}")
    private String serverId;

    @Value("${spring.profiles.active:default}")
    private String profile;

    public TradeReceiverService(ReferenceLookupClient referenceLookupClient,
                                CheckEligibleClient checkEligibleClient) {
        this.referenceLookupClient = referenceLookupClient;
        this.checkEligibleClient = checkEligibleClient;
    }

    @CircuitBreaker(name = "tradeProcessing", fallbackMethod = "processTradesFallback")
    @Retry(name = "tradeProcessing")
    public TradeResponse processTrade(TradeRequest request) {
        log.info("Processing trade: {} on {}/{}", request.tradeId(), serverId, profile);

        try {
            // Step 1: Get reference data for the instrument
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

            // Step 3: Create response
            TradeResponse response = TradeResponse.success(
                    request.tradeId(),
                    refData,
                    eligibility.eligible(),
                    serverId,
                    profile
            );

            tradeCache.put(request.tradeId(), response);
            return response;

        } catch (Exception e) {
            log.error("Error processing trade {}: {}", request.tradeId(), e.getMessage(), e);
            TradeResponse errorResponse = TradeResponse.error(
                    request.tradeId(),
                    "Error processing trade: " + e.getMessage(),
                    serverId,
                    profile
            );
            tradeCache.put(request.tradeId(), errorResponse);
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

    public Optional<TradeResponse> getTradeStatus(String tradeId) {
        return Optional.ofNullable(tradeCache.get(tradeId));
    }

    // Fallback methods

    private TradeResponse processTradesFallback(TradeRequest request, Throwable t) {
        log.warn("Fallback triggered for trade processing: {} - {}", request.tradeId(), t.getMessage());
        return TradeResponse.error(
                request.tradeId(),
                "Service temporarily unavailable. Please retry later.",
                serverId,
                profile
        );
    }

    private ReferenceData getInstrumentDataFallback(String instrument, Throwable t) {
        log.warn("Fallback triggered for reference lookup: {} - {}", instrument, t.getMessage());
        return ReferenceData.of("FALLBACK", "INSTRUMENT", instrument, "Fallback data", serverId);
    }

    private EligibilityResponse checkEligibilityFallback(EligibilityRequest request, Throwable t) {
        log.warn("Fallback triggered for eligibility check: {} - {}", request.tradeId(), t.getMessage());
        // In fallback, we reject the trade for safety
        return EligibilityResponse.ineligible(
                request.tradeId(),
                java.util.List.of("Eligibility service unavailable - trade rejected for safety"),
                serverId
        );
    }
}
