package com.bmo.rdp.common.loadbalancer;

import com.bmo.rdp.common.model.ServiceInstanceInfo;
import com.bmo.rdp.common.registry.ServiceInstanceRegistry;
import com.bmo.rdp.common.resilience.PassiveHealthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local-First Load Balancer implementation with Passive Health support.
 *
 * Strategy:
 * 1. First attempt: Use LOCAL instance (same server) if available, healthy, and not already tried
 * 2. Fallback: Use REMOTE instance with lowest load (excluding already tried instances)
 *
 * Passive Health:
 * - Tracks the selected instance in PassiveHealthContext for failure tracking
 * - Excludes instances that were already tried in this retry cycle
 */
public class LocalFirstLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    private static final Logger log = LoggerFactory.getLogger(LocalFirstLoadBalancer.class);

    private final String serviceId;
    private final ServiceInstanceRegistry registry;

    public LocalFirstLoadBalancer(String serviceId, ServiceInstanceRegistry registry) {
        this.serviceId = serviceId;
        this.registry = registry;
    }

    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        return Mono.fromSupplier(() -> {
            PassiveHealthContext.RequestContext context = PassiveHealthContext.current();
            Set<String> triedInstances = context.getTriedInstances();

            // Try local instance first (if not already tried)
            ServiceInstanceInfo localInstance = registry.getLocalInstance(serviceId);
            if (localInstance != null && localInstance.isHealthy()
                    && !triedInstances.contains(localInstance.getInstanceId())) {
                log.debug("Selected LOCAL instance for {}: {}", serviceId, localInstance.getInstanceId());
                // Track this instance in context for passive health
                context.setCurrentInstance(serviceId, localInstance.getInstanceId());
                return new DefaultResponse(toServiceInstance(localInstance));
            }

            // Log if local was skipped due to being already tried
            if (localInstance != null && triedInstances.contains(localInstance.getInstanceId())) {
                log.debug("Skipping LOCAL instance {} (already tried in this retry cycle)",
                        localInstance.getInstanceId());
            }

            // Fallback to remote instances sorted by load (excluding tried instances)
            List<ServiceInstanceInfo> remoteInstances = registry.getRemoteInstancesByLoad(serviceId);
            for (ServiceInstanceInfo instance : remoteInstances) {
                if (!triedInstances.contains(instance.getInstanceId())) {
                    log.debug("Selected REMOTE instance for {} (load={}): {}",
                            serviceId, instance.getCurrentLoad(), instance.getInstanceId());
                    // Track this instance in context for passive health
                    context.setCurrentInstance(serviceId, instance.getInstanceId());
                    return new DefaultResponse(toServiceInstance(instance));
                }
                log.debug("Skipping REMOTE instance {} (already tried in this retry cycle)",
                        instance.getInstanceId());
            }

            log.warn("No healthy instances available for service: {} (tried: {})",
                    serviceId, triedInstances);
            return new EmptyResponse();
        });
    }

    /**
     * Convert ServiceInstanceInfo to Spring Cloud ServiceInstance.
     */
    private ServiceInstance toServiceInstance(ServiceInstanceInfo info) {
        return new ServiceInstance() {
            @Override
            public String getServiceId() {
                return info.getServiceId();
            }

            @Override
            public String getHost() {
                return info.getHost();
            }

            @Override
            public int getPort() {
                return info.getPort();
            }

            @Override
            public boolean isSecure() {
                return false;
            }

            @Override
            public URI getUri() {
                return URI.create(info.getUri());
            }

            @Override
            public Map<String, String> getMetadata() {
                return Map.of(
                        "instanceId", info.getInstanceId(),
                        "local", String.valueOf(info.isLocal()),
                        "load", String.valueOf(info.getCurrentLoad())
                );
            }

            @Override
            public String getInstanceId() {
                return info.getInstanceId();
            }
        };
    }
}
