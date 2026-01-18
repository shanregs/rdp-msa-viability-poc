package com.bmo.rdp.common.model;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe representation of a service instance with health and load metrics.
 */
public class ServiceInstanceInfo {

    private final String serviceId;
    private final String instanceId;
    private final String host;
    private final int port;
    private final boolean local;
    private final AtomicBoolean healthy;
    private final AtomicInteger currentLoad;
    private final AtomicLong lastHealthCheck;

    public ServiceInstanceInfo(String serviceId, String instanceId, String host, int port, boolean local) {
        this.serviceId = serviceId;
        this.instanceId = instanceId;
        this.host = host;
        this.port = port;
        this.local = local;
        this.healthy = new AtomicBoolean(true);
        this.currentLoad = new AtomicInteger(0);
        this.lastHealthCheck = new AtomicLong(System.currentTimeMillis());
    }

    public String getServiceId() {
        return serviceId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isLocal() {
        return local;
    }

    public boolean isHealthy() {
        return healthy.get();
    }

    public void setHealthy(boolean healthy) {
        this.healthy.set(healthy);
        this.lastHealthCheck.set(System.currentTimeMillis());
    }

    public int getCurrentLoad() {
        return currentLoad.get();
    }

    public void setCurrentLoad(int load) {
        this.currentLoad.set(load);
    }

    public void incrementLoad() {
        this.currentLoad.incrementAndGet();
    }

    public void decrementLoad() {
        this.currentLoad.decrementAndGet();
    }

    public long getLastHealthCheck() {
        return lastHealthCheck.get();
    }

    public String getUri() {
        return "http://" + host + ":" + port;
    }

    @Override
    public String toString() {
        return String.format("ServiceInstanceInfo{serviceId='%s', instanceId='%s', host='%s', port=%d, local=%s, healthy=%s, load=%d}",
                serviceId, instanceId, host, port, local, healthy.get(), currentLoad.get());
    }
}
