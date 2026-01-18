package com.bmo.rdp.eqtradehandler.controller;

import com.bmo.rdp.eqtradehandler.dto.RegulatoryRequest;
import com.bmo.rdp.eqtradehandler.dto.RegulatoryResponse;
import com.bmo.rdp.eqtradehandler.service.EqTradeHandlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for EQ trade handling and regulatory processing.
 */
@RestController
@RequestMapping("/api/v1/regulatory")
public class EqTradeHandlerController {

    private static final Logger log = LoggerFactory.getLogger(EqTradeHandlerController.class);

    private final EqTradeHandlerService service;

    @Value("${server.id:unknown}")
    private String serverId;

    public EqTradeHandlerController(EqTradeHandlerService service) {
        this.service = service;
    }

    @PostMapping("/process")
    public ResponseEntity<RegulatoryResponse> processRegulatory(@RequestBody RegulatoryRequest request) {
        log.info("[{}] Processing regulatory request for trade: {}", serverId, request.tradeId());
        RegulatoryResponse response = service.processRegulatory(request);
        log.info("[{}] Regulatory processing complete for {}: {}", serverId, request.tradeId(), response.status());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{tradeId}")
    public ResponseEntity<RegulatoryResponse> getProcessingStatus(@PathVariable String tradeId) {
        log.info("[{}] Getting regulatory status for trade: {}", serverId, tradeId);
        return service.getProcessingStatus(tradeId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/health/instance")
    public ResponseEntity<Map<String, String>> getInstanceInfo() {
        return ResponseEntity.ok(Map.of(
                "service", "eq-trade-handler-service",
                "serverId", serverId
        ));
    }
}
