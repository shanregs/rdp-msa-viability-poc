package com.bmo.rdp.tradereceiver.service;

import com.bmo.rdp.common.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for processing incoming trades.
 * Uses ResilientServiceClient for calls to Reference Lookup and Check Eligible services
 * with retry and circuit breaker support.
 */
@Service
public class TradeReceiverService {

    private static final Logger log = LoggerFactory.getLogger(TradeReceiverService.class);

    private final ResilientServiceClient resilientServiceClient;

    // Cache of processed trades
    private final Map<String, TradeResponse> tradeCache = new ConcurrentHashMap<>();

    @Value("${server.id:unknown}")
    private String serverId;

    @Value("${spring.profiles.active:default}")
    private String profile;

    public TradeReceiverService(ResilientServiceClient resilientServiceClient) {
        this.resilientServiceClient = resilientServiceClient;
    }

    /**
     * Process a trade request.
     *
     * The ResilientServiceClient handles retry and circuit breaker logic.
     * If all retries fail, the circuit breaker fallback returns a safe response.
     */
    public TradeResponse processTrade(TradeRequest request) {
        log.info("Processing trade: {} on {}/{}", request.tradeId(), serverId, profile);

        try {
            // Step 1: Get reference data for the instrument (with retry/circuit breaker)
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
            // This catch block handles unexpected errors not handled by Resilience4j
            // Circuit breaker fallbacks should prevent most exceptions from reaching here
            log.error("Unexpected error processing trade {}: {}", request.tradeId(), e.getMessage(), e);
            TradeResponse errorResponse = TradeResponse.error(
                    request.tradeId(),
                    "Unexpected error: " + e.getMessage(),
                    serverId,
                    profile
            );
            tradeCache.put(request.tradeId(), errorResponse);
            return errorResponse;
        }
    }

    public Optional<TradeResponse> getTradeStatus(String tradeId) {
        return Optional.ofNullable(tradeCache.get(tradeId));
    }
}
