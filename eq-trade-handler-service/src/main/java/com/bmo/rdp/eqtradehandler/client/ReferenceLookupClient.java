package com.bmo.rdp.eqtradehandler.client;

import com.bmo.rdp.common.dto.ReferenceData;
import com.bmo.rdp.eqtradehandler.config.FeignClientConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * Feign client for Reference Lookup Service.
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
