package com.bmo.rdp.common.registry;

import com.bmo.rdp.common.model.ServiceInstanceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Thread-safe registry for tracking service instances with their health and load status.
 * This is the central component for the local-first load balancing strategy.
 */
@Component
public class ServiceInstanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ServiceInstanceRegistry.class);

    private final Map<String, List<ServiceInstanceInfo>> instances = new ConcurrentHashMap<>();

    /**
     * Register a new service instance.
     */
    public void registerInstance(ServiceInstanceInfo instance) {
        instances.computeIfAbsent(instance.getServiceId(), k -> new CopyOnWriteArrayList<>())
                .add(instance);
        log.info("Registered instance: {}", instance);
    }

    /**
     * Update the instances for a service (typically after fetching from Eureka).
     */
    public void updateInstances(String serviceId, List<ServiceInstanceInfo> newInstances) {
        instances.put(serviceId, new CopyOnWriteArrayList<>(newInstances));
        log.debug("Updated {} instances for service: {}", newInstances.size(), serviceId);
    }

    /**
     * Get all instances for a service.
     */
    public List<ServiceInstanceInfo> getInstances(String serviceId) {
        return instances.getOrDefault(serviceId, Collections.emptyList());
    }

    /**
     * Get only healthy instances for a service.
     */
    public List<ServiceInstanceInfo> getHealthyInstances(String serviceId) {
        return getInstances(serviceId).stream()
                .filter(ServiceInstanceInfo::isHealthy)
                .collect(Collectors.toList());
    }

    /**
     * Get the local instance for a service (if available).
     */
    public ServiceInstanceInfo getLocalInstance(String serviceId) {
        return getHealthyInstances(serviceId).stream()
                .filter(ServiceInstanceInfo::isLocal)
                .findFirst()
                .orElse(null);
    }

    /**
     * Get remote instances sorted by load (ascending).
     */
    public List<ServiceInstanceInfo> getRemoteInstancesByLoad(String serviceId) {
        return getHealthyInstances(serviceId).stream()
                .filter(i -> !i.isLocal())
                .sorted(Comparator.comparingInt(ServiceInstanceInfo::getCurrentLoad))
                .collect(Collectors.toList());
    }

    /**
     * Update health status for an instance.
     */
    public void updateHealth(String serviceId, String instanceId, boolean healthy) {
        getInstances(serviceId).stream()
                .filter(i -> i.getInstanceId().equals(instanceId))
                .findFirst()
                .ifPresent(i -> {
                    boolean wasHealthy = i.isHealthy();
                    i.setHealthy(healthy);
                    // Log transitions at appropriate levels
                    if (wasHealthy && !healthy) {
                        log.warn("Instance {} marked UNHEALTHY (passive health update)", instanceId);
                    } else if (!wasHealthy && healthy) {
                        log.info("Instance {} recovered and marked HEALTHY", instanceId);
                    } else {
                        log.debug("Updated health for {}: {}", instanceId, healthy);
                    }
                });
    }

    /**
     * Mark an instance as unhealthy (used by passive health pattern).
     * Returns true if the instance was found and updated.
     */
    public boolean markUnhealthy(String serviceId, String instanceId) {
        return getInstances(serviceId).stream()
                .filter(i -> i.getInstanceId().equals(instanceId))
                .findFirst()
                .map(i -> {
                    if (i.isHealthy()) {
                        i.setHealthy(false);
                        log.warn("PASSIVE HEALTH: Instance {} marked UNHEALTHY", instanceId);
                        return true;
                    }
                    return false;
                })
                .orElse(false);
    }

    /**
     * Mark an instance as healthy (used by health monitor recovery).
     * Returns true if the instance was found and updated.
     */
    public boolean markHealthy(String serviceId, String instanceId) {
        return getInstances(serviceId).stream()
                .filter(i -> i.getInstanceId().equals(instanceId))
                .findFirst()
                .map(i -> {
                    if (!i.isHealthy()) {
                        i.setHealthy(true);
                        log.info("RECOVERY: Instance {} marked HEALTHY", instanceId);
                        return true;
                    }
                    return false;
                })
                .orElse(false);
    }

    /**
     * Update load for an instance.
     */
    public void updateLoad(String serviceId, String instanceId, int load) {
        getInstances(serviceId).stream()
                .filter(i -> i.getInstanceId().equals(instanceId))
                .findFirst()
                .ifPresent(i -> {
                    i.setCurrentLoad(load);
                    log.debug("Updated load for {}: {}", instanceId, load);
                });
    }

    /**
     * Remove an instance from the registry.
     */
    public void deregisterInstance(String serviceId, String instanceId) {
        List<ServiceInstanceInfo> serviceInstances = instances.get(serviceId);
        if (serviceInstances != null) {
            serviceInstances.removeIf(i -> i.getInstanceId().equals(instanceId));
            log.info("Deregistered instance: {} from service: {}", instanceId, serviceId);
        }
    }

    /**
     * Get all registered service IDs.
     */
    public List<String> getServiceIds() {
        return List.copyOf(instances.keySet());
    }

    /**
     * Clear all instances for a service.
     */
    public void clearService(String serviceId) {
        instances.remove(serviceId);
    }
}
