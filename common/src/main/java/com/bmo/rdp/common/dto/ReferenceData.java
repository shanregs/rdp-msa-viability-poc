package com.bmo.rdp.common.dto;

import java.time.LocalDateTime;

/**
 * Reference data DTO returned by Reference Lookup Service.
 */
public record ReferenceData(
        String id,
        String type,
        String code,
        String description,
        String status,
        LocalDateTime lastUpdated,
        String serverId
) {
    public static ReferenceData of(String id, String type, String code, String description, String serverId) {
        return new ReferenceData(id, type, code, description, "ACTIVE", LocalDateTime.now(), serverId);
    }
}
