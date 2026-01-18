package com.bmo.rdp.referencelookup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Reference Lookup Service - provides reference data to other services.
 * Deployed on Server 1 and Server 3 for redundancy.
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ReferenceLookupApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReferenceLookupApplication.class, args);
    }
}
