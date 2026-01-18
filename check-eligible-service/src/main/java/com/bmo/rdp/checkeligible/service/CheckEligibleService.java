package com.bmo.rdp.checkeligible.service;

import com.bmo.rdp.common.dto.EligibilityRequest;
import com.bmo.rdp.common.dto.EligibilityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for performing eligibility checks.
 * In a real application, this would contain complex business rules.
 */
@Service
public class CheckEligibleService {

    private static final Logger log = LoggerFactory.getLogger(CheckEligibleService.class);

    // Cache of eligibility results
    private final Map<String, EligibilityResponse> eligibilityCache = new ConcurrentHashMap<>();

    // Blacklisted counterparties (for demo purposes)
    private static final Set<String> BLACKLISTED_COUNTERPARTIES = Set.of("BLOCKED_CP");

    // Maximum trade amount limits by currency
    private static final Map<String, BigDecimal> AMOUNT_LIMITS = Map.of(
            "USD", new BigDecimal("10000000"),
            "EUR", new BigDecimal("8000000"),
            "GBP", new BigDecimal("7000000"),
            "JPY", new BigDecimal("1000000000")
    );

    @Value("${server.id:unknown}")
    private String serverId;

    public EligibilityResponse checkEligibility(EligibilityRequest request) {
        List<String> reasons = new ArrayList<>();

        // Rule 1: Check counterparty blacklist
        if (BLACKLISTED_COUNTERPARTIES.contains(request.counterparty())) {
            reasons.add("Counterparty is blacklisted: " + request.counterparty());
        }

        // Rule 2: Check amount limits
        BigDecimal limit = AMOUNT_LIMITS.getOrDefault(request.currency(), new BigDecimal("5000000"));
        if (request.amount() != null && request.amount().compareTo(limit) > 0) {
            reasons.add(String.format("Amount exceeds limit for %s: %s > %s",
                    request.currency(), request.amount(), limit));
        }

        // Rule 3: Check trade type validity
        Set<String> validTradeTypes = Set.of("EQUITY", "FOREX", "IRSWAP", "BOND", "OPTION");
        if (request.tradeType() == null || !validTradeTypes.contains(request.tradeType().toUpperCase())) {
            reasons.add("Invalid trade type: " + request.tradeType());
        }

        // Rule 4: Simulate random eligibility checks (10% failure rate for demo)
        if (Math.random() < 0.1) {
            reasons.add("Random compliance check failed");
        }

        // Create and cache the response
        EligibilityResponse response;
        if (reasons.isEmpty()) {
            response = EligibilityResponse.eligible(request.tradeId(), serverId);
        } else {
            response = EligibilityResponse.ineligible(request.tradeId(), reasons, serverId);
        }

        eligibilityCache.put(request.tradeId(), response);
        return response;
    }

    public Optional<EligibilityResponse> getEligibilityStatus(String tradeId) {
        return Optional.ofNullable(eligibilityCache.get(tradeId));
    }
}
