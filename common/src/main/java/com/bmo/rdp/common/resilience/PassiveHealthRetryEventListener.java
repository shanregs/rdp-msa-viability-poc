package com.bmo.rdp.common.resilience;

import com.bmo.rdp.common.registry.ServiceInstanceRegistry;
import io.github.resilience4j.core.EventConsumer;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.retry.event.RetryEvent;
import io.github.resilience4j.retry.event.RetryOnErrorEvent;
import io.github.resilience4j.retry.event.RetryOnRetryEvent;
import io.github.resilience4j.retry.event.RetryOnSuccessEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Listens to Resilience4j retry events to implement passive health updates.
 *
 * On each retry attempt (failure):
 * 1. Marks the current instance as unhealthy
 * 2. Adds it to the tried instances set so load balancer skips it
 *
 * On success or final error:
 * 1. Clears the PassiveHealthContext for the next request
 */
@Component
public class PassiveHealthRetryEventListener {

    private static final Logger log = LoggerFactory.getLogger(PassiveHealthRetryEventListener.class);

    private final RetryRegistry retryRegistry;
    private final ServiceInstanceRegistry instanceRegistry;

    public PassiveHealthRetryEventListener(RetryRegistry retryRegistry,
                                           ServiceInstanceRegistry instanceRegistry) {
        this.retryRegistry = retryRegistry;
        this.instanceRegistry = instanceRegistry;
    }

    @PostConstruct
    public void init() {
        // Register event consumers for all retry instances
        retryRegistry.getAllRetries().forEach(this::registerEventConsumers);

        // Also register for any future retry instances
        retryRegistry.getEventPublisher()
                .onEntryAdded(event -> registerEventConsumers(event.getAddedEntry()));

        log.info("PassiveHealthRetryEventListener initialized for {} retry instances",
                retryRegistry.getAllRetries().size());
    }

    private void registerEventConsumers(Retry retry) {
        retry.getEventPublisher()
                .onRetry(this::onRetry)
                .onSuccess(this::onSuccess)
                .onError(this::onError);

        log.debug("Registered passive health event consumers for retry: {}", retry.getName());
    }

    /**
     * Called on each retry attempt (after a failure, before the next attempt).
     */
    private void onRetry(RetryOnRetryEvent event) {
        PassiveHealthContext.RequestContext context = PassiveHealthContext.current();
        String instanceId = context.getCurrentInstanceId();
        String serviceId = context.getCurrentServiceId();

        if (instanceId != null && serviceId != null) {
            // Mark instance as tried
            context.markTried(instanceId);

            // Mark instance as unhealthy (passive health)
            instanceRegistry.markUnhealthy(serviceId, instanceId);

            log.info("PASSIVE HEALTH FAILOVER: {} retry #{} - instance {} marked unhealthy, " +
                     "trying next instance. Cause: {}",
                    event.getName(),
                    event.getNumberOfRetryAttempts(),
                    instanceId,
                    event.getLastThrowable().getMessage());
        }
    }

    /**
     * Called when the operation succeeds (either on first attempt or after retries).
     */
    private void onSuccess(RetryOnSuccessEvent event) {
        if (event.getNumberOfRetryAttempts() > 0) {
            log.info("PASSIVE HEALTH: {} succeeded after {} retries",
                    event.getName(), event.getNumberOfRetryAttempts());
        }

        // Clear context for next request
        PassiveHealthContext.clear();
    }

    /**
     * Called when all retry attempts are exhausted.
     */
    private void onError(RetryOnErrorEvent event) {
        PassiveHealthContext.RequestContext context = PassiveHealthContext.current();
        String instanceId = context.getCurrentInstanceId();
        String serviceId = context.getCurrentServiceId();

        // Mark the last tried instance as unhealthy
        if (instanceId != null && serviceId != null) {
            context.markTried(instanceId);
            instanceRegistry.markUnhealthy(serviceId, instanceId);
        }

        log.error("PASSIVE HEALTH: {} exhausted all {} retries. Tried instances: {}. Final error: {}",
                event.getName(),
                event.getNumberOfRetryAttempts(),
                context.getTriedInstances(),
                event.getLastThrowable().getMessage());

        // Clear context for next request
        PassiveHealthContext.clear();
    }
}
