package com.bmo.rdp.common.eureka;

import com.bmo.rdp.common.model.ServiceInstanceInfo;
import com.bmo.rdp.common.registry.ServiceInstanceRegistry;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.shared.Application;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Syncs service instances from Eureka to the local ServiceInstanceRegistry.
 * This enables the local-first load balancing strategy.
 */
@Component
@ConditionalOnClass(EurekaClient.class)
public class EurekaRegistrySync {

    private static final Logger log = LoggerFactory.getLogger(EurekaRegistrySync.class);

    private final EurekaClient eurekaClient;
    private final ServiceInstanceRegistry registry;

    @Value("${server.id:unknown}")
    private String serverId;

    @Value("${eureka.sync.services:reference-lookup-service,check-eligible-service}")
    private List<String> servicesToSync;

    public EurekaRegistrySync(EurekaClient eurekaClient, ServiceInstanceRegistry registry) {
        this.eurekaClient = eurekaClient;
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        log.info("EurekaRegistrySync initialized for server: {}, syncing services: {}", serverId, servicesToSync);
    }

    /**
     * Sync instances from Eureka every 30 seconds.
     */
    @Scheduled(fixedDelayString = "${eureka.sync.interval:30000}", initialDelay = 5000)
    public void syncInstances() {
        for (String serviceId : servicesToSync) {
            try {
                syncService(serviceId);
            } catch (Exception e) {
                log.warn("Failed to sync service {}: {}", serviceId, e.getMessage());
            }
        }
    }

    private void syncService(String serviceId) {
        Application application = eurekaClient.getApplication(serviceId.toUpperCase());
        if (application == null) {
            log.warn("No instances found in Eureka for service: {}", serviceId);
            return;
        }

        List<ServiceInstanceInfo> instances = application.getInstances().stream()
                .map(instance -> {
                    String instanceServerId = instance.getMetadata().getOrDefault("server-id", "unknown");
                    boolean isLocal = serverId.equals(instanceServerId);

                    return new ServiceInstanceInfo(
                            serviceId,
                            instance.getInstanceId(),
                            instance.getHostName(),
                            instance.getPort(),
                            isLocal
                    );
                })
                .collect(Collectors.toList());

        registry.updateInstances(serviceId, instances);
        log.info("Synced {} instances for service {} (local: {})",
                instances.size(), serviceId,
                instances.stream().filter(ServiceInstanceInfo::isLocal).count());
    }

    /**
     * Force immediate sync (useful for testing).
     */
    public void forceSync() {
        syncInstances();
    }
}
