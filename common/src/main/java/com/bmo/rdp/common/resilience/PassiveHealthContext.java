package com.bmo.rdp.common.resilience;

import java.util.HashSet;
import java.util.Set;

/**
 * Thread-local context for tracking instances during a retry cycle.
 *
 * This enables passive health by:
 * 1. Tracking which instances have been tried (to avoid retrying failed instances)
 * 2. Recording the current target instance (for marking unhealthy on failure)
 */
public class PassiveHealthContext {

    private static final ThreadLocal<RequestContext> CONTEXT = ThreadLocal.withInitial(RequestContext::new);

    /**
     * Get the current request context.
     */
    public static RequestContext current() {
        return CONTEXT.get();
    }

    /**
     * Clear the context (call after request completes).
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * Context for a single request/retry cycle.
     */
    public static class RequestContext {
        private final Set<String> triedInstances = new HashSet<>();
        private String currentInstanceId;
        private String currentServiceId;

        /**
         * Mark an instance as tried (failed).
         */
        public void markTried(String instanceId) {
            if (instanceId != null) {
                triedInstances.add(instanceId);
            }
        }

        /**
         * Check if an instance has already been tried.
         */
        public boolean hasTried(String instanceId) {
            return instanceId != null && triedInstances.contains(instanceId);
        }

        /**
         * Get all tried instances.
         */
        public Set<String> getTriedInstances() {
            return Set.copyOf(triedInstances);
        }

        /**
         * Set the current target instance (before making request).
         */
        public void setCurrentInstance(String serviceId, String instanceId) {
            this.currentServiceId = serviceId;
            this.currentInstanceId = instanceId;
        }

        /**
         * Get the current instance ID being called.
         */
        public String getCurrentInstanceId() {
            return currentInstanceId;
        }

        /**
         * Get the current service ID being called.
         */
        public String getCurrentServiceId() {
            return currentServiceId;
        }

        /**
         * Reset for a new service call (within the same request).
         */
        public void resetForNewService() {
            triedInstances.clear();
            currentInstanceId = null;
            currentServiceId = null;
        }
    }
}
