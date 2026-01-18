package com.bmo.rdp.common.exception;

/**
 * Exception thrown when a service is unavailable.
 */
public class ServiceUnavailableException extends RuntimeException {

    private final String serviceId;

    public ServiceUnavailableException(String serviceId) {
        super("Service unavailable: " + serviceId);
        this.serviceId = serviceId;
    }

    public ServiceUnavailableException(String serviceId, Throwable cause) {
        super("Service unavailable: " + serviceId, cause);
        this.serviceId = serviceId;
    }

    public String getServiceId() {
        return serviceId;
    }
}
