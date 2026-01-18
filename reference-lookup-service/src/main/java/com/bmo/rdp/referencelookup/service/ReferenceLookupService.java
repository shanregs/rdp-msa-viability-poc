package com.bmo.rdp.referencelookup.service;

import com.bmo.rdp.common.dto.ReferenceData;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for managing reference data.
 * In a real application, this would connect to a database.
 */
@Service
public class ReferenceLookupService {

    private static final Logger log = LoggerFactory.getLogger(ReferenceLookupService.class);

    // In-memory reference data store (simulating a database)
    private final Map<String, Map<String, ReferenceData>> referenceDataStore = new ConcurrentHashMap<>();

    @Value("${server.id:unknown}")
    private String serverId;

    @PostConstruct
    public void initializeData() {
        log.info("Initializing reference data on server: {}", serverId);

        // Initialize instrument data
        Map<String, ReferenceData> instruments = new HashMap<>();
        instruments.put("AAPL", ReferenceData.of("INS001", "INSTRUMENT", "AAPL", "Apple Inc.", serverId));
        instruments.put("GOOGL", ReferenceData.of("INS002", "INSTRUMENT", "GOOGL", "Alphabet Inc.", serverId));
        instruments.put("MSFT", ReferenceData.of("INS003", "INSTRUMENT", "MSFT", "Microsoft Corp.", serverId));
        instruments.put("EUR/USD", ReferenceData.of("INS004", "INSTRUMENT", "EUR/USD", "Euro/US Dollar", serverId));
        instruments.put("GBP/USD", ReferenceData.of("INS005", "INSTRUMENT", "GBP/USD", "British Pound/US Dollar", serverId));
        instruments.put("IRS-5Y", ReferenceData.of("INS006", "INSTRUMENT", "IRS-5Y", "5-Year Interest Rate Swap", serverId));
        referenceDataStore.put("INSTRUMENT", instruments);

        // Initialize counterparty data
        Map<String, ReferenceData> counterparties = new HashMap<>();
        counterparties.put("CP001", ReferenceData.of("CP001", "COUNTERPARTY", "CP001", "Goldman Sachs", serverId));
        counterparties.put("CP002", ReferenceData.of("CP002", "COUNTERPARTY", "CP002", "JP Morgan", serverId));
        counterparties.put("CP003", ReferenceData.of("CP003", "COUNTERPARTY", "CP003", "Morgan Stanley", serverId));
        counterparties.put("CP004", ReferenceData.of("CP004", "COUNTERPARTY", "CP004", "Citadel", serverId));
        referenceDataStore.put("COUNTERPARTY", counterparties);

        // Initialize currency data
        Map<String, ReferenceData> currencies = new HashMap<>();
        currencies.put("USD", ReferenceData.of("CUR001", "CURRENCY", "USD", "US Dollar", serverId));
        currencies.put("EUR", ReferenceData.of("CUR002", "CURRENCY", "EUR", "Euro", serverId));
        currencies.put("GBP", ReferenceData.of("CUR003", "CURRENCY", "GBP", "British Pound", serverId));
        currencies.put("JPY", ReferenceData.of("CUR004", "CURRENCY", "JPY", "Japanese Yen", serverId));
        referenceDataStore.put("CURRENCY", currencies);

        log.info("Reference data initialized with {} types", referenceDataStore.size());
    }

    public List<ReferenceData> getByType(String type) {
        Map<String, ReferenceData> typeData = referenceDataStore.get(type.toUpperCase());
        if (typeData == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(typeData.values());
    }

    public Optional<ReferenceData> getByTypeAndCode(String type, String code) {
        Map<String, ReferenceData> typeData = referenceDataStore.get(type.toUpperCase());
        if (typeData == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(typeData.get(code.toUpperCase()));
    }

    public Optional<ReferenceData> getInstrumentData(String instrument) {
        return getByTypeAndCode("INSTRUMENT", instrument);
    }

    public Optional<ReferenceData> getCounterpartyData(String counterparty) {
        return getByTypeAndCode("COUNTERPARTY", counterparty);
    }

    public Optional<ReferenceData> getCurrencyData(String currency) {
        return getByTypeAndCode("CURRENCY", currency);
    }
}
