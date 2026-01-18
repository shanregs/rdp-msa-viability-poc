package com.bmo.rdp.common.dto;

import java.math.BigDecimal;

/**
 * Request DTO for eligibility check.
 */
public record EligibilityRequest(
        String tradeId,
        String tradeType,
        String counterparty,
        BigDecimal amount,
        String currency
) {
}
