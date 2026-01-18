package com.bmo.rdp.common.loadbalancer;

import com.bmo.rdp.common.model.ServiceInstanceInfo;
import com.bmo.rdp.common.registry.ServiceInstanceRegistry;
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

/**
 * Local-First Load Balancer implementation.
 *
 * Strategy:
 * 1. First attempt: Use LOCAL instance (same server) if available and healthy
 * 2. Fallback: Use REMOTE instance with lowest load
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
            // Try local instance first
            ServiceInstanceInfo localInstance = registry.getLocalInstance(serviceId);
            if (localInstance != null && localInstance.isHealthy()) {
                log.debug("Selected LOCAL instance for {}: {}", serviceId, localInstance.getInstanceId());
                return new DefaultResponse(toServiceInstance(localInstance));
            }

            // Fallback to remote instances sorted by load
            List<ServiceInstanceInfo> remoteInstances = registry.getRemoteInstancesByLoad(serviceId);
            if (!remoteInstances.isEmpty()) {
                ServiceInstanceInfo selected = remoteInstances.get(0);
                log.debug("Selected REMOTE instance for {} (load={}): {}",
                        serviceId, selected.getCurrentLoad(), selected.getInstanceId());
                return new DefaultResponse(toServiceInstance(selected));
            }

            log.warn("No healthy instances available for service: {}", serviceId);
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
