package com.bmo.rdp.tradereceiver.controller;

import com.bmo.rdp.common.dto.TradeRequest;
import com.bmo.rdp.common.dto.TradeResponse;
import com.bmo.rdp.tradereceiver.service.TradeReceiverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for trade ingestion.
 */
@RestController
@RequestMapping("/api/v1/trades")
public class TradeReceiverController {

    private static final Logger log = LoggerFactory.getLogger(TradeReceiverController.class);

    private final TradeReceiverService service;

    @Value("${server.id:unknown}")
    private String serverId;

    @Value("${spring.profiles.active:default}")
    private String profile;

    public TradeReceiverController(TradeReceiverService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TradeResponse> receiveTrade(@RequestBody TradeRequest request) {
        log.info("[{}/{}] Receiving trade: {}", serverId, profile, request.tradeId());
        TradeResponse response = service.processTrade(request);
        log.info("[{}/{}] Trade processed: {} - {}", serverId, profile, request.tradeId(), response.status());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{tradeId}")
    public ResponseEntity<TradeResponse> getTradeStatus(@PathVariable String tradeId) {
        log.info("[{}/{}] Getting trade status: {}", serverId, profile, tradeId);
        return service.getTradeStatus(tradeId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/health/instance")
    public ResponseEntity<Map<String, String>> getInstanceInfo() {
        return ResponseEntity.ok(Map.of(
                "service", "trade-receiver-service",
                "serverId", serverId,
                "profile", profile
        ));
    }
}
