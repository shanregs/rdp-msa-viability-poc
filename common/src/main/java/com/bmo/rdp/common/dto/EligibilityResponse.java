package com.bmo.rdp.common.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for eligibility check.
 */
public record EligibilityResponse(
        String tradeId,
        boolean eligible,
        String status,
        List<String> reasons,
        LocalDateTime checkedAt,
        String serverId
) {
    public static EligibilityResponse eligible(String tradeId, String serverId) {
        return new EligibilityResponse(tradeId, true, "ELIGIBLE", List.of(), LocalDateTime.now(), serverId);
    }

    public static EligibilityResponse ineligible(String tradeId, List<String> reasons, String serverId) {
        return new EligibilityResponse(tradeId, false, "INELIGIBLE", reasons, LocalDateTime.now(), serverId);
    }
}
