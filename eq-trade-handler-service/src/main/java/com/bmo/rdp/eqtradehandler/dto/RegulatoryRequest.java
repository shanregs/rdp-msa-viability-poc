package com.bmo.rdp.eqtradehandler.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request DTO for regulatory processing.
 */
public record RegulatoryRequest(
        String tradeId,
        String tradeType,
        String instrument,
        String counterparty,
        BigDecimal quantity,
        BigDecimal price,
        String currency,
        LocalDate tradeDate,
        LocalDate settlementDate,
        String regulationType
) {
}
