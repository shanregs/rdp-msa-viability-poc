package com.bmo.rdp.checkeligible.controller;

import com.bmo.rdp.checkeligible.service.CheckEligibleService;
import com.bmo.rdp.common.dto.EligibilityRequest;
import com.bmo.rdp.common.dto.EligibilityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for eligibility checks.
 */
@RestController
@RequestMapping("/api/v1/eligibility")
public class CheckEligibleController {

    private static final Logger log = LoggerFactory.getLogger(CheckEligibleController.class);

    private final CheckEligibleService service;

    @Value("${server.id:unknown}")
    private String serverId;

    public CheckEligibleController(CheckEligibleService service) {
        this.service = service;
    }

    @PostMapping("/check")
    public ResponseEntity<EligibilityResponse> checkEligibility(@RequestBody EligibilityRequest request) {
        log.info("[{}] Checking eligibility for trade: {}", serverId, request.tradeId());
        EligibilityResponse response = service.checkEligibility(request);
        log.info("[{}] Eligibility result for {}: {}", serverId, request.tradeId(), response.eligible());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{tradeId}")
    public ResponseEntity<EligibilityResponse> getEligibilityStatus(@PathVariable String tradeId) {
        log.info("[{}] Getting eligibility status for trade: {}", serverId, tradeId);
        return service.getEligibilityStatus(tradeId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/health/instance")
    public ResponseEntity<String> getInstanceInfo() {
        return ResponseEntity.ok("Check Eligible Service running on: " + serverId);
    }
}
