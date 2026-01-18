package com.bmo.rdp.tradereceiver.config;

import com.bmo.rdp.common.loadbalancer.LocalFirstLoadBalancerConfig;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClients;
import org.springframework.context.annotation.Configuration;

/**
 * Load balancer configuration that applies local-first strategy
 * to both reference-lookup-service and check-eligible-service.
 */
@Configuration
@LoadBalancerClients({
        @LoadBalancerClient(name = "reference-lookup-service", configuration = LocalFirstLoadBalancerConfig.class),
        @LoadBalancerClient(name = "check-eligible-service", configuration = LocalFirstLoadBalancerConfig.class)
})
public class LoadBalancerConfig {
}
