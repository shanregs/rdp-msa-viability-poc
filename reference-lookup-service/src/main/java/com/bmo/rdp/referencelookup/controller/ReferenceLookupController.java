package com.bmo.rdp.referencelookup.controller;

import com.bmo.rdp.referencelookup.service.ReferenceLookupService;
import com.bmo.rdp.common.dto.ReferenceData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for reference data lookups.
 */
@RestController
@RequestMapping("/api/v1/refdata")
public class ReferenceLookupController {

    private static final Logger log = LoggerFactory.getLogger(ReferenceLookupController.class);

    private final ReferenceLookupService service;

    @Value("${server.id:unknown}")
    private String serverId;

    public ReferenceLookupController(ReferenceLookupService service) {
        this.service = service;
    }

    @GetMapping("/{type}")
    public ResponseEntity<List<ReferenceData>> getByType(@PathVariable String type) {
        log.info("[{}] Getting reference data for type: {}", serverId, type);
        return ResponseEntity.ok(service.getByType(type));
    }

    @GetMapping("/{type}/{code}")
    public ResponseEntity<ReferenceData> getByTypeAndCode(
            @PathVariable String type,
            @PathVariable String code) {
        log.info("[{}] Getting reference data for type: {}, code: {}", serverId, type, code);
        return service.getByTypeAndCode(type, code)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/instrument/{instrument}")
    public ResponseEntity<ReferenceData> getInstrumentData(@PathVariable String instrument) {
        log.info("[{}] Getting instrument data: {}", serverId, instrument);
        return service.getInstrumentData(instrument)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/counterparty/{counterparty}")
    public ResponseEntity<ReferenceData> getCounterpartyData(@PathVariable String counterparty) {
        log.info("[{}] Getting counterparty data: {}", serverId, counterparty);
        return service.getCounterpartyData(counterparty)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/health/instance")
    public ResponseEntity<String> getInstanceInfo() {
        return ResponseEntity.ok("Reference Lookup Service running on: " + serverId);
    }
}
