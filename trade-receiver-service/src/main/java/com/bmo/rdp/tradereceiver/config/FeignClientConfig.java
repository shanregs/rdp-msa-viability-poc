package com.bmo.rdp.tradereceiver.config;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Configuration for Feign clients.
 */
@Configuration
public class FeignClientConfig {

    @Bean
    public Logger.Level feignLoggerLevel() {
        return Logger.Level.BASIC;
    }

    @Bean
    public Request.Options requestOptions() {
        return new Request.Options(
                5, TimeUnit.SECONDS,  // connect timeout
                10, TimeUnit.SECONDS, // read timeout
                true                   // follow redirects
        );
    }

    @Bean
    public Retryer retryer() {
        // Disable Feign's built-in retry as we use Resilience4j
        return Retryer.NEVER_RETRY;
    }
}
