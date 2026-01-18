package com.bmo.rdp.tradereceiver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Trade Receiver Service - entry point for trade data.
 * Deployed with different profiles:
 * - Server 1: equity (port 8082)
 * - Server 2: forex (port 8083)
 * - Server 3: irswap (port 8082)
 * - Server 4: supporteventhandler (port 8084)
 */
@SpringBootApplication(scanBasePackages = {"com.bmo.rdp.tradereceiver", "com.bmo.rdp.common"})
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
public class TradeReceiverApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradeReceiverApplication.class, args);
    }
}
