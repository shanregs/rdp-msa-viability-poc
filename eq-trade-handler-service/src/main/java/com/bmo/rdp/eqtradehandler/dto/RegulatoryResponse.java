package com.bmo.rdp.eqtradehandler.dto;

import com.bmo.rdp.common.dto.ReferenceData;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for regulatory processing.
 */
public record RegulatoryResponse(
        String tradeId,
        String status,
        String regulationType,
        boolean compliant,
        List<String> findings,
        ReferenceData referenceData,
        LocalDateTime processedAt,
        String serverId
) {
    public static RegulatoryResponse compliant(String tradeId, String regulationType,
                                               ReferenceData refData, String serverId) {
        return new RegulatoryResponse(
                tradeId,
                "COMPLIANT",
                regulationType,
                true,
                List.of(),
                refData,
                LocalDateTime.now(),
                serverId
        );
    }

    public static RegulatoryResponse nonCompliant(String tradeId, String regulationType,
                                                  List<String> findings, String serverId) {
        return new RegulatoryResponse(
                tradeId,
                "NON_COMPLIANT",
                regulationType,
                false,
                findings,
                null,
                LocalDateTime.now(),
                serverId
        );
    }

    public static RegulatoryResponse error(String tradeId, String message, String serverId) {
        return new RegulatoryResponse(
                tradeId,
                "ERROR",
                null,
                false,
                List.of(message),
                null,
                LocalDateTime.now(),
                serverId
        );
    }
}
