package com.bmo.rdp.common.loadbalancer;

import com.bmo.rdp.common.registry.ServiceInstanceRegistry;
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Configuration class for Local-First Load Balancer.
 * This should be used with @LoadBalancerClient annotation.
 */
public class LocalFirstLoadBalancerConfig {

    @Bean
    public ReactorServiceInstanceLoadBalancer localFirstLoadBalancer(
            Environment environment,
            LoadBalancerClientFactory loadBalancerClientFactory,
            ServiceInstanceRegistry registry) {

        String serviceId = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        return new LocalFirstLoadBalancer(serviceId, registry);
    }
}
