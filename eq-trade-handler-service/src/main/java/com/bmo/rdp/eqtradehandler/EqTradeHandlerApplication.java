package com.bmo.rdp.eqtradehandler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * EQ Trade Handler Service - handles equity trade processing and regulatory compliance.
 * Deployed on Server 2 (port 8090).
 */
@SpringBootApplication(scanBasePackages = {"com.bmo.rdp.eqtradehandler", "com.bmo.rdp.common"})
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
public class EqTradeHandlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EqTradeHandlerApplication.class, args);
    }
}
