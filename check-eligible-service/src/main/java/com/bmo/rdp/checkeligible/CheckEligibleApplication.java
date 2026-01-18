package com.bmo.rdp.checkeligible;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Check Eligible Service - performs eligibility checks for trades.
 * Deployed on Server 1 and Server 3 for redundancy.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class CheckEligibleApplication {

    public static void main(String[] args) {
        SpringApplication.run(CheckEligibleApplication.class, args);
    }
}
