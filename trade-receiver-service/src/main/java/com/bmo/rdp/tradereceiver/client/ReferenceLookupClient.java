package com.bmo.rdp.tradereceiver.client;

import com.bmo.rdp.common.dto.ReferenceData;
import com.bmo.rdp.tradereceiver.config.FeignClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * Feign client for Reference Lookup Service.
 * Uses local-first load balancing via custom configuration.
 */
@FeignClient(
        name = "reference-lookup-service",
        configuration = FeignClientConfig.class
)
public interface ReferenceLookupClient {

    @GetMapping("/api/v1/refdata/{type}")
    List<ReferenceData> getByType(@PathVariable("type") String type);

    @GetMapping("/api/v1/refdata/{type}/{code}")
    ReferenceData getByTypeAndCode(@PathVariable("type") String type, @PathVariable("code") String code);

    @GetMapping("/api/v1/refdata/instrument/{instrument}")
    ReferenceData getInstrumentData(@PathVariable("instrument") String instrument);

    @GetMapping("/api/v1/refdata/counterparty/{counterparty}")
    ReferenceData getCounterpartyData(@PathVariable("counterparty") String counterparty);
}
