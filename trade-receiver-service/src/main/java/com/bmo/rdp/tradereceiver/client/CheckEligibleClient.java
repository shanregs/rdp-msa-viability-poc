package com.bmo.rdp.tradereceiver.client;

import com.bmo.rdp.common.dto.EligibilityRequest;
import com.bmo.rdp.common.dto.EligibilityResponse;
import com.bmo.rdp.tradereceiver.config.FeignClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Feign client for Check Eligible Service.
 * Uses local-first load balancing via custom configuration.
 */
@FeignClient(
        name = "check-eligible-service",
        configuration = FeignClientConfig.class
)
public interface CheckEligibleClient {

    @PostMapping("/api/v1/eligibility/check")
    EligibilityResponse checkEligibility(@RequestBody EligibilityRequest request);

    @GetMapping("/api/v1/eligibility/{tradeId}")
    EligibilityResponse getEligibilityStatus(@PathVariable("tradeId") String tradeId);
}
