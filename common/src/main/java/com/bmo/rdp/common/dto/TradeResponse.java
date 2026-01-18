package com.bmo.rdp.common.dto;

import java.time.LocalDateTime;

/**
 * Trade response DTO after processing.
 */
public record TradeResponse(
        String tradeId,
        String status,
        String message,
        boolean eligible,
        ReferenceData referenceData,
        LocalDateTime processedAt,
        String serverId,
        String profile
) {
    public static TradeResponse success(String tradeId, ReferenceData refData, boolean eligible,
                                        String serverId, String profile) {
        return new TradeResponse(
                tradeId,
                "PROCESSED",
                "Trade processed successfully",
                eligible,
                refData,
                LocalDateTime.now(),
                serverId,
                profile
        );
    }

    public static TradeResponse error(String tradeId, String message, String serverId, String profile) {
        return new TradeResponse(
                tradeId,
                "ERROR",
                message,
                false,
                null,
                LocalDateTime.now(),
                serverId,
                profile
        );
    }
}
