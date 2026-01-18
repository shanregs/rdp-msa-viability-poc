package com.bmo.rdp.common.health;

import com.bmo.rdp.common.model.ServiceInstanceInfo;
import com.bmo.rdp.common.registry.ServiceInstanceRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background health monitoring service using ExecutorService.
 * Periodically checks health of all registered service instances
 * and updates the ServiceInstanceRegistry.
 */
@Service
public class HealthMonitorService {

    private static final Logger log = LoggerFactory.getLogger(HealthMonitorService.class);

    private final ServiceInstanceRegistry registry;
    private final RestClient restClient;
    private final ScheduledExecutorService executor;

    @Value("${health.monitor.interval:10}")
    private int healthCheckIntervalSeconds;

    @Value("${health.monitor.timeout:5}")
    private int healthCheckTimeoutSeconds;

    @Value("${health.monitor.enabled:true}")
    private boolean healthMonitorEnabled;

    public HealthMonitorService(ServiceInstanceRegistry registry) {
        this.registry = registry;
        this.restClient = RestClient.builder()
                .defaultHeader("Accept", "application/json")
                .build();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "health-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    public void start() {
        if (healthMonitorEnabled) {
            log.info("Starting health monitor with interval: {}s, timeout: {}s",
                    healthCheckIntervalSeconds, healthCheckTimeoutSeconds);
            executor.scheduleAtFixedRate(
                    this::performHealthChecks,
                    healthCheckIntervalSeconds,
                    healthCheckIntervalSeconds,
                    TimeUnit.SECONDS
            );
        } else {
            log.info("Health monitor is disabled");
        }
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping health monitor");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Perform health checks for all registered instances.
     */
    private void performHealthChecks() {
        try {
            List<String> serviceIds = registry.getServiceIds();
            for (String serviceId : serviceIds) {
                List<ServiceInstanceInfo> instances = registry.getInstances(serviceId);
                for (ServiceInstanceInfo instance : instances) {
                    checkInstanceHealth(instance);
                }
            }
        } catch (Exception e) {
            log.error("Error during health check cycle", e);
        }
    }

    /**
     * Check health of a single instance.
     */
    private void checkInstanceHealth(ServiceInstanceInfo instance) {
        String healthUrl = instance.getUri() + "/actuator/health";
        try {
            Map<String, Object> response = restClient.get()
                    .uri(healthUrl)
                    .retrieve()
                    .body(Map.class);

            if (response != null && "UP".equals(response.get("status"))) {
                if (!instance.isHealthy()) {
                    log.info("Instance {} is now healthy", instance.getInstanceId());
                }
                instance.setHealthy(true);
            } else {
                markUnhealthy(instance);
            }
        } catch (Exception e) {
            log.warn("Health check failed for {}: {}", instance.getInstanceId(), e.getMessage());
            markUnhealthy(instance);
        }
    }

    private void markUnhealthy(ServiceInstanceInfo instance) {
        if (instance.isHealthy()) {
            log.warn("Instance {} is now unhealthy", instance.getInstanceId());
        }
        instance.setHealthy(false);
    }

    /**
     * Force a health check immediately (useful for testing or manual triggers).
     */
    public void forceHealthCheck() {
        executor.execute(this::performHealthChecks);
    }
}
