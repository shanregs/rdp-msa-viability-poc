package com.bmo.rdp.common.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Trade request DTO for ingestion.
 */
public record TradeRequest(
        String tradeId,
        String tradeType,
        String instrument,
        String counterparty,
        BigDecimal quantity,
        BigDecimal price,
        String currency,
        LocalDate tradeDate,
        LocalDate settlementDate
) {
}
