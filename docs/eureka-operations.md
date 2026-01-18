# Eureka Operations & Service Call Flow

This document explains how Eureka Server handles service registration, discovery operations, and how client services use this information for making resilient service calls with retry logic.

## Table of Contents

- [1. Eureka Server Operations](#1-eureka-server-operations)
  - [1.1 Register Operation](#11-register-operation)
  - [1.2 Heartbeat (Renew) Operation](#12-heartbeat-renew-operation)
  - [1.3 Fetch Registry Operation](#13-fetch-registry-operation)
  - [1.4 Deregister Operation](#14-deregister-operation)
- [2. Client-Side Service Discovery](#2-client-side-service-discovery)
- [3. Service Call Flow with Load Balancing](#3-service-call-flow-with-load-balancing)
- [4. Retry Mechanism with Service Discovery](#4-retry-mechanism-with-service-discovery)
- [5. Complete End-to-End Flow](#5-complete-end-to-end-flow)

---

## 1. Eureka Server Operations

### 1.1 Register Operation

When a service instance starts, it registers itself with the Eureka Server.

#### Sequence Diagram

```
┌─────────────────┐          ┌─────────────────┐          ┌─────────────────┐
│  RefData        │          │  Eureka Server  │          │  Eureka Server  │
│  Service        │          │  (Server 1)     │          │  (Server 4)     │
│  (Server 1)     │          │  :8088          │          │  :8088          │
└────────┬────────┘          └────────┬────────┘          └────────┬────────┘
         │                            │                            │
         │  1. Application Starts     │                            │
         │  ─────────────────────     │                            │
         │                            │                            │
         │  2. POST /eureka/apps/REFDATA-SERVICE                   │
         │  ──────────────────────────────────────────────────────►│
         │     Body: InstanceInfo JSON                             │
         │     {                                                   │
         │       "instanceId": "server1:refdata:8081",             │
         │       "hostName": "server1",                            │
         │       "app": "REFDATA-SERVICE",                         │
         │       "ipAddr": "172.18.0.2",                           │
         │       "port": { "$": 8081, "@enabled": true },          │
         │       "status": "UP",                                   │
         │       "healthCheckUrl": "http://server1:8081/actuator/health",
         │       "metadata": { "server-id": "server1" }            │
         │     }                                                   │
         │                            │                            │
         │                            │  3. Validate InstanceInfo  │
         │                            │  ────────────────────────  │
         │                            │                            │
         │                            │  4. Store in Registry      │
         │                            │  ─────────────────────     │
         │                            │  registry.put(             │
         │                            │    "REFDATA-SERVICE",      │
         │                            │    instanceId,             │
         │                            │    Lease<InstanceInfo>     │
         │                            │  )                         │
         │                            │                            │
         │                            │  5. Peer Replication       │
         │                            │─────────────────────────────►
         │                            │  POST /eureka/apps/...     │
         │                            │  (replication header)      │
         │                            │                            │
         │                            │◄─────────────────────────────
         │                            │  200 OK                    │
         │                            │                            │
         │  6. 204 No Content         │                            │
         │◄──────────────────────────────────────────────────────────
         │                            │                            │
         │  7. Start Heartbeat Timer  │                            │
         │  ────────────────────────  │                            │
         │  (every 30 seconds)        │                            │
         │                            │                            │
```

#### Eureka Server Internal Processing

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                        EUREKA SERVER - REGISTER OPERATION                                   │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  ApplicationResource.addInstance()                                                    │  │
│  │  ─────────────────────────────────                                                    │  │
│  │                                                                                       │  │
│  │  @POST                                                                                │  │
│  │  @Path("/{appName}")                                                                  │  │
│  │  public Response addInstance(InstanceInfo info) {                                     │  │
│  │                                                                                       │  │
│  │      // 1. Validate the incoming InstanceInfo                                         │  │
│  │      if (info == null || info.getId() == null) {                                      │  │
│  │          return Response.status(400).build();                                         │  │
│  │      }                                                                                │  │
│  │                                                                                       │  │
│  │      // 2. Register with the registry                                                 │  │
│  │      registry.register(info, "true".equals(isReplication));                           │  │
│  │                                                                                       │  │
│  │      // 3. Return success                                                             │  │
│  │      return Response.status(204).build();                                             │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                          │                                                  │
│                                          ▼                                                  │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  PeerAwareInstanceRegistryImpl.register()                                             │  │
│  │  ────────────────────────────────────────                                             │  │
│  │                                                                                       │  │
│  │  public void register(InstanceInfo info, boolean isReplication) {                    │  │
│  │                                                                                       │  │
│  │      // 1. Get or create the application map                                         │  │
│  │      Map<String, Lease<InstanceInfo>> gMap = registry.get(info.getAppName());        │  │
│  │      if (gMap == null) {                                                             │  │
│  │          gMap = new ConcurrentHashMap<>();                                           │  │
│  │          registry.put(info.getAppName(), gMap);                                      │  │
│  │      }                                                                                │  │
│  │                                                                                       │  │
│  │      // 2. Create lease with registration timestamp                                  │  │
│  │      Lease<InstanceInfo> lease = new Lease<>(info, durationInSecs);                  │  │
│  │      lease.setServiceUpTimestamp(System.currentTimeMillis());                        │  │
│  │                                                                                       │  │
│  │      // 3. Store in registry                                                         │  │
│  │      gMap.put(info.getId(), lease);                                                  │  │
│  │                                                                                       │  │
│  │      // 4. Invalidate cache                                                          │  │
│  │      invalidateCache(info.getAppName());                                             │  │
│  │                                                                                       │  │
│  │      // 5. Replicate to peers (if not already a replication)                         │  │
│  │      if (!isReplication) {                                                           │  │
│  │          replicateToPeers(Action.Register, info);                                    │  │
│  │      }                                                                                │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Registry Data Structure After Registration                                           │  │
│  │  ──────────────────────────────────────────                                           │  │
│  │                                                                                       │  │
│  │  ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>> registry                │  │
│  │                                                                                       │  │
│  │  {                                                                                    │  │
│  │    "REFDATA-SERVICE": {                                                              │  │
│  │      "server1:refdata:8081": Lease {                                                 │  │
│  │        holder: InstanceInfo { ... },                                                 │  │
│  │        registrationTimestamp: 1704067200000,                                         │  │
│  │        lastUpdateTimestamp: 1704067200000,                                           │  │
│  │        evictionTimestamp: 0,                                                         │  │
│  │        duration: 90000  // 90 seconds                                                │  │
│  │      }                                                                                │  │
│  │    }                                                                                  │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 1.2 Heartbeat (Renew) Operation

Services send heartbeats every 30 seconds to maintain their lease.

#### Sequence Diagram

```
┌─────────────────┐          ┌─────────────────┐
│  RefData        │          │  Eureka Server  │
│  Service        │          │  (Server 1)     │
└────────┬────────┘          └────────┬────────┘
         │                            │
         │  Every 30 seconds          │
         │  ────────────────          │
         │                            │
         │  PUT /eureka/apps/REFDATA-SERVICE/server1:refdata:8081
         │───────────────────────────►│
         │  Query params:             │
         │    status=UP               │
         │    lastDirtyTimestamp=xxx  │
         │                            │
         │                            │  1. Find lease in registry
         │                            │  ─────────────────────────
         │                            │  lease = registry
         │                            │    .get("REFDATA-SERVICE")
         │                            │    .get("server1:refdata:8081")
         │                            │
         │                            │  2. Renew the lease
         │                            │  ─────────────────
         │                            │  lease.renew()
         │                            │  // Updates lastUpdateTimestamp
         │                            │
         │                            │  3. Check for dirty timestamp
         │                            │  ───────────────────────────
         │                            │  if (lastDirtyTimestamp >
         │                            │      lease.lastDirtyTimestamp)
         │                            │    return 404 (trigger re-register)
         │                            │
         │  200 OK                    │
         │◄───────────────────────────│
         │                            │
         │                            │
         │  ─── 30 seconds later ───  │
         │                            │
         │  PUT /eureka/apps/REFDATA-SERVICE/server1:refdata:8081
         │───────────────────────────►│
         │                            │
         │  200 OK                    │
         │◄───────────────────────────│
         │                            │
```

#### Eureka Server Internal Processing

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                        EUREKA SERVER - HEARTBEAT OPERATION                                  │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  InstanceResource.renewLease()                                                        │  │
│  │  ─────────────────────────────                                                        │  │
│  │                                                                                       │  │
│  │  @PUT                                                                                 │  │
│  │  public Response renewLease(                                                          │  │
│  │      @QueryParam("status") String status,                                            │  │
│  │      @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {                  │  │
│  │                                                                                       │  │
│  │      // 1. Attempt to renew the lease                                                │  │
│  │      boolean success = registry.renew(appName, instanceId);                          │  │
│  │                                                                                       │  │
│  │      // 2. If not found, return 404 (client should re-register)                      │  │
│  │      if (!success) {                                                                 │  │
│  │          return Response.status(404).build();                                        │  │
│  │      }                                                                                │  │
│  │                                                                                       │  │
│  │      // 3. Handle status override or dirty timestamp sync                            │  │
│  │      Response response = validateDirtyTimestamp(lastDirtyTimestamp);                 │  │
│  │      if (response != null) return response;                                          │  │
│  │                                                                                       │  │
│  │      return Response.ok().build();                                                   │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                          │                                                  │
│                                          ▼                                                  │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  AbstractInstanceRegistry.renew()                                                     │  │
│  │  ────────────────────────────────                                                     │  │
│  │                                                                                       │  │
│  │  public boolean renew(String appName, String id) {                                   │  │
│  │                                                                                       │  │
│  │      // 1. Get the lease from registry                                               │  │
│  │      Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);                  │  │
│  │      if (gMap == null) return false;                                                 │  │
│  │                                                                                       │  │
│  │      Lease<InstanceInfo> lease = gMap.get(id);                                       │  │
│  │      if (lease == null) return false;                                                │  │
│  │                                                                                       │  │
│  │      // 2. Renew the lease (update timestamp)                                        │  │
│  │      lease.renew();  // lastUpdateTimestamp = System.currentTimeMillis()             │  │
│  │                                                                                       │  │
│  │      return true;                                                                    │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Lease Eviction Timer (Background Thread)                                             │  │
│  │  ────────────────────────────────────────                                             │  │
│  │                                                                                       │  │
│  │  // Runs every 60 seconds                                                            │  │
│  │  EvictionTask.run() {                                                                │  │
│  │                                                                                       │  │
│  │      for each (appName, instances) in registry {                                     │  │
│  │          for each (instanceId, lease) in instances {                                 │  │
│  │                                                                                       │  │
│  │              // Check if lease has expired                                           │  │
│  │              // Default: 90 seconds without heartbeat                                │  │
│  │              if (lease.isExpired(additionalLeaseMs)) {                               │  │
│  │                                                                                       │  │
│  │                  // Evict the instance                                               │  │
│  │                  internalCancel(appName, instanceId);                                │  │
│  │                  logger.warn("Evicting instance: {}", instanceId);                   │  │
│  │              }                                                                        │  │
│  │          }                                                                            │  │
│  │      }                                                                                │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  │  // Lease expiry check                                                               │  │
│  │  Lease.isExpired(additionalLeaseMs) {                                                │  │
│  │      return (evictionTimestamp > 0 ||                                                │  │
│  │              System.currentTimeMillis() >                                            │  │
│  │              (lastUpdateTimestamp + duration + additionalLeaseMs));                  │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Timeline: Heartbeat and Eviction                                                     │  │
│  │  ────────────────────────────────                                                     │  │
│  │                                                                                       │  │
│  │  Time ────────────────────────────────────────────────────────────────────────────►  │  │
│  │                                                                                       │  │
│  │  0s        30s       60s       90s       120s      150s                              │  │
│  │  │         │         │         │         │         │                                 │  │
│  │  ▼         ▼         ▼         ▼         ▼         ▼                                 │  │
│  │  ┌─┐       ┌─┐       ┌─┐       ┌─┐       ┌─┐       ┌─┐                               │  │
│  │  │R│       │H│       │H│       │H│       │H│       │H│  Normal Operation             │  │
│  │  └─┘       └─┘       └─┘       └─┘       └─┘       └─┘                               │  │
│  │                                                                                       │  │
│  │  R = Register, H = Heartbeat                                                         │  │
│  │                                                                                       │  │
│  │  ─────────────────────────────────────────────────────────────────────────────────   │  │
│  │                                                                                       │  │
│  │  Failure Scenario:                                                                   │  │
│  │                                                                                       │  │
│  │  0s        30s       60s       90s       120s      150s                              │  │
│  │  │         │         │         │         │         │                                 │  │
│  │  ▼         ▼         ▼         ▼         ▼         ▼                                 │  │
│  │  ┌─┐       ┌─┐       ╳         ╳         ╳         ┌─┐                               │  │
│  │  │R│       │H│       │         │         │         │E│  Instance Evicted             │  │
│  │  └─┘       └─┘       │         │         │         └─┘                               │  │
│  │                      │         │         │                                           │  │
│  │                      └─────────┴─────────┘                                           │  │
│  │                        No heartbeats                                                 │  │
│  │                        (90s expiry)                                                  │  │
│  │                                                                                       │  │
│  │  E = Evicted from registry                                                           │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 1.3 Fetch Registry Operation

Client services fetch the registry to discover available service instances.

#### Sequence Diagram

```
┌─────────────────┐          ┌─────────────────┐
│  Ingestion      │          │  Eureka Server  │
│  Service        │          │  (Server 1)     │
└────────┬────────┘          └────────┬────────┘
         │                            │
         │  1. Application Starts     │
         │  ─────────────────────     │
         │                            │
         │  2. GET /eureka/apps       │
         │───────────────────────────►│  Full registry fetch
         │  Accept: application/json  │
         │                            │
         │                            │  3. Build response from cache
         │                            │  ───────────────────────────
         │                            │
         │  4. Applications JSON      │
         │◄───────────────────────────│
         │  {                         │
         │    "applications": {       │
         │      "application": [      │
         │        {                   │
         │          "name": "REFDATA-SERVICE",
         │          "instance": [...]│
         │        },                  │
         │        {                   │
         │          "name": "ELIGIBILITY-SERVICE",
         │          "instance": [...]│
         │        }                   │
         │      ]                     │
         │    }                       │
         │  }                         │
         │                            │
         │  5. Cache locally          │
         │  ────────────────          │
         │  localRegistry.store(apps) │
         │                            │
         │                            │
         │  ─── 30 seconds later ───  │
         │  (Delta fetch interval)    │
         │                            │
         │  6. GET /eureka/apps/delta │
         │───────────────────────────►│  Incremental updates only
         │                            │
         │  7. Delta changes          │
         │◄───────────────────────────│
         │  (only changed instances)  │
         │                            │
         │  8. Merge with local cache │
         │  ─────────────────────────│
         │                            │
```

#### Eureka Server Internal Processing

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                        EUREKA SERVER - FETCH REGISTRY OPERATION                             │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  ApplicationsResource.getContainers()                                                 │  │
│  │  ────────────────────────────────────                                                 │  │
│  │                                                                                       │  │
│  │  @GET                                                                                 │  │
│  │  @Path("/apps")                                                                       │  │
│  │  public Response getApplications(@HeaderParam("Accept") String acceptHeader) {        │  │
│  │                                                                                       │  │
│  │      // 1. Check response cache first (for performance)                               │  │
│  │      String cacheKey = buildCacheKey(acceptHeader);                                   │  │
│  │      String payload = responseCache.get(cacheKey);                                    │  │
│  │                                                                                       │  │
│  │      if (payload != null) {                                                           │  │
│  │          return Response.ok(payload).build();                                         │  │
│  │      }                                                                                │  │
│  │                                                                                       │  │
│  │      // 2. Build applications from registry                                           │  │
│  │      Applications apps = registry.getApplications();                                  │  │
│  │                                                                                       │  │
│  │      // 3. Serialize and cache                                                        │  │
│  │      payload = serialize(apps, acceptHeader);                                         │  │
│  │      responseCache.put(cacheKey, payload);                                            │  │
│  │                                                                                       │  │
│  │      return Response.ok(payload).build();                                             │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                          │                                                  │
│                                          ▼                                                  │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  AbstractInstanceRegistry.getApplications()                                           │  │
│  │  ──────────────────────────────────────────                                           │  │
│  │                                                                                       │  │
│  │  public Applications getApplications() {                                              │  │
│  │      Applications apps = new Applications();                                          │  │
│  │                                                                                       │  │
│  │      // Iterate through all registered applications                                   │  │
│  │      for (Entry<String, Map<String, Lease<InstanceInfo>>> entry :                     │  │
│  │           registry.entrySet()) {                                                      │  │
│  │                                                                                       │  │
│  │          Application app = new Application(entry.getKey());                           │  │
│  │                                                                                       │  │
│  │          // Add all instances for this application                                    │  │
│  │          for (Entry<String, Lease<InstanceInfo>> instanceEntry :                      │  │
│  │               entry.getValue().entrySet()) {                                          │  │
│  │                                                                                       │  │
│  │              Lease<InstanceInfo> lease = instanceEntry.getValue();                    │  │
│  │              InstanceInfo info = lease.getHolder();                                   │  │
│  │                                                                                       │  │
│  │              // Only include UP instances (or as configured)                          │  │
│  │              if (info.getStatus() == InstanceStatus.UP) {                             │  │
│  │                  app.addInstance(info);                                               │  │
│  │              }                                                                        │  │
│  │          }                                                                            │  │
│  │                                                                                       │  │
│  │          apps.addApplication(app);                                                    │  │
│  │      }                                                                                │  │
│  │                                                                                       │  │
│  │      apps.setAppsHashCode(computeAppsHashCode());                                     │  │
│  │      return apps;                                                                     │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Client-Side Caching (DiscoveryClient)                                                │  │
│  │  ─────────────────────────────────────                                                │  │
│  │                                                                                       │  │
│  │  class DiscoveryClient {                                                             │  │
│  │                                                                                       │  │
│  │      private AtomicReference<Applications> localRegionApps;                          │  │
│  │                                                                                       │  │
│  │      // Scheduler runs every 30 seconds                                              │  │
│  │      void refreshRegistry() {                                                        │  │
│  │                                                                                       │  │
│  │          // First call: full fetch                                                   │  │
│  │          if (localRegionApps.get() == null) {                                        │  │
│  │              Applications apps = eurekaClient.getApplications();                     │  │
│  │              localRegionApps.set(apps);                                              │  │
│  │              return;                                                                 │  │
│  │          }                                                                            │  │
│  │                                                                                       │  │
│  │          // Subsequent calls: delta fetch                                            │  │
│  │          Applications delta = eurekaClient.getDelta();                               │  │
│  │                                                                                       │  │
│  │          // Merge delta with local cache                                             │  │
│  │          Applications currentApps = localRegionApps.get();                           │  │
│  │          for (Application app : delta.getRegisteredApplications()) {                 │  │
│  │              for (InstanceInfo instance : app.getInstances()) {                      │  │
│  │                  switch (instance.getActionType()) {                                 │  │
│  │                      case ADDED:                                                     │  │
│  │                      case MODIFIED:                                                  │  │
│  │                          currentApps.addInstance(instance);                          │  │
│  │                          break;                                                      │  │
│  │                      case DELETED:                                                   │  │
│  │                          currentApps.removeInstance(instance);                       │  │
│  │                          break;                                                      │  │
│  │                  }                                                                    │  │
│  │              }                                                                        │  │
│  │          }                                                                            │  │
│  │                                                                                       │  │
│  │          // Verify hash code matches server                                          │  │
│  │          if (!delta.getAppsHashCode().equals(currentApps.computeHashCode())) {       │  │
│  │              // Hash mismatch - do full fetch                                        │  │
│  │              localRegionApps.set(eurekaClient.getApplications());                    │  │
│  │          }                                                                            │  │
│  │      }                                                                                │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

### 1.4 Deregister Operation

When a service instance shuts down gracefully, it deregisters from Eureka.

#### Sequence Diagram

```
┌─────────────────┐          ┌─────────────────┐          ┌─────────────────┐
│  RefData        │          │  Eureka Server  │          │  Eureka Server  │
│  Service        │          │  (Server 1)     │          │  (Server 4)     │
└────────┬────────┘          └────────┬────────┘          └────────┬────────┘
         │                            │                            │
         │  1. Shutdown Signal        │                            │
         │  (SIGTERM / app shutdown)  │                            │
         │  ──────────────────────    │                            │
         │                            │                            │
         │  2. @PreDestroy callback   │                            │
         │  ─────────────────────     │                            │
         │  eurekaClient.shutdown()   │                            │
         │                            │                            │
         │  3. DELETE /eureka/apps/REFDATA-SERVICE/server1:refdata:8081
         │───────────────────────────►│                            │
         │                            │                            │
         │                            │  4. Remove from registry   │
         │                            │  ────────────────────────  │
         │                            │  registry                  │
         │                            │    .get("REFDATA-SERVICE") │
         │                            │    .remove(instanceId)     │
         │                            │                            │
         │                            │  5. Invalidate cache       │
         │                            │  ─────────────────         │
         │                            │                            │
         │                            │  6. Peer Replication       │
         │                            │─────────────────────────────►
         │                            │  DELETE /eureka/apps/...   │
         │                            │                            │
         │                            │◄─────────────────────────────
         │                            │  200 OK                    │
         │                            │                            │
         │  7. 200 OK                 │                            │
         │◄───────────────────────────│                            │
         │                            │                            │
         │  8. Application exits      │                            │
         │  ────────────────────      │                            │
         │                            │                            │
```

#### Eureka Server Internal Processing

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                        EUREKA SERVER - DEREGISTER OPERATION                                 │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  InstanceResource.cancelLease()                                                       │  │
│  │  ──────────────────────────────                                                       │  │
│  │                                                                                       │  │
│  │  @DELETE                                                                              │  │
│  │  public Response cancelLease() {                                                      │  │
│  │                                                                                       │  │
│  │      // 1. Cancel the lease                                                           │  │
│  │      boolean success = registry.cancel(appName, instanceId, isReplication);           │  │
│  │                                                                                       │  │
│  │      if (!success) {                                                                  │  │
│  │          return Response.status(404).build();                                         │  │
│  │      }                                                                                │  │
│  │                                                                                       │  │
│  │      return Response.ok().build();                                                    │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                          │                                                  │
│                                          ▼                                                  │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  PeerAwareInstanceRegistryImpl.cancel()                                               │  │
│  │  ──────────────────────────────────────                                               │  │
│  │                                                                                       │  │
│  │  public boolean cancel(String appName, String id, boolean isReplication) {            │  │
│  │                                                                                       │  │
│  │      // 1. Cancel in local registry                                                   │  │
│  │      boolean success = internalCancel(appName, id);                                   │  │
│  │                                                                                       │  │
│  │      // 2. Replicate to peers                                                         │  │
│  │      if (success && !isReplication) {                                                 │  │
│  │          replicateToPeers(Action.Cancel, appName, id);                                │  │
│  │      }                                                                                │  │
│  │                                                                                       │  │
│  │      return success;                                                                  │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  │  protected boolean internalCancel(String appName, String id) {                        │  │
│  │                                                                                       │  │
│  │      Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);                   │  │
│  │      if (gMap == null) return false;                                                  │  │
│  │                                                                                       │  │
│  │      Lease<InstanceInfo> lease = gMap.remove(id);                                     │  │
│  │      if (lease == null) return false;                                                 │  │
│  │                                                                                       │  │
│  │      // Mark eviction timestamp                                                       │  │
│  │      lease.cancel();                                                                  │  │
│  │                                                                                       │  │
│  │      // Add to recently cancelled queue (for delta)                                   │  │
│  │      recentlyCancelledQueue.add(new Pair<>(                                           │  │
│  │          System.currentTimeMillis(),                                                  │  │
│  │          lease.getHolder()                                                            │  │
│  │      ));                                                                              │  │
│  │                                                                                       │  │
│  │      // Invalidate response cache                                                     │  │
│  │      invalidateCache(appName);                                                        │  │
│  │                                                                                       │  │
│  │      return true;                                                                     │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Client-Side Service Discovery

How services discover and cache available instances locally.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                        CLIENT-SIDE SERVICE DISCOVERY                                        │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Ingestion Service - Discovery Client Setup                                           │  │
│  │  ──────────────────────────────────────────                                           │  │
│  │                                                                                       │  │
│  │  @SpringBootApplication                                                               │  │
│  │  @EnableDiscoveryClient                                                               │  │
│  │  @EnableFeignClients                                                                  │  │
│  │  public class IngestionServiceApplication {                                           │  │
│  │      // Spring Cloud auto-configures:                                                 │  │
│  │      // - EurekaClient (service registration)                                         │  │
│  │      // - DiscoveryClient (service discovery)                                         │  │
│  │      // - LoadBalancerClient (client-side LB)                                         │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Local Registry Cache Structure                                                       │  │
│  │  ────────────────────────────────                                                     │  │
│  │                                                                                       │  │
│  │  ┌─────────────────────────────────────────────────────────────────────────────────┐  │  │
│  │  │                     Ingestion Service (Server 1)                                │  │  │
│  │  │                                                                                 │  │  │
│  │  │   ┌─────────────────────────────────────────────────────────────────────────┐   │  │  │
│  │  │   │  Local Cache: AtomicReference<Applications>                             │   │  │  │
│  │  │   │                                                                         │   │  │  │
│  │  │   │  ┌─────────────────────────────────────────────────────────────────┐    │   │  │  │
│  │  │   │  │  REFDATA-SERVICE                                                │    │   │  │  │
│  │  │   │  │  ├── server1:refdata:8081 (172.18.0.2:8081) [LOCAL] ✓           │    │   │  │  │
│  │  │   │  │  └── server3:refdata:8081 (172.18.0.4:8081) [REMOTE]            │    │   │  │  │
│  │  │   │  └─────────────────────────────────────────────────────────────────┘    │   │  │  │
│  │  │   │                                                                         │   │  │  │
│  │  │   │  ┌─────────────────────────────────────────────────────────────────┐    │   │  │  │
│  │  │   │  │  ELIGIBILITY-SERVICE                                            │    │   │  │  │
│  │  │   │  │  ├── server1:eligibility:8092 (172.18.0.2:8092) [LOCAL] ✓       │    │   │  │  │
│  │  │   │  │  └── server3:eligibility:8092 (172.18.0.4:8092) [REMOTE]        │    │   │  │  │
│  │  │   │  └─────────────────────────────────────────────────────────────────┘    │   │  │  │
│  │  │   │                                                                         │   │  │  │
│  │  │   │  ┌─────────────────────────────────────────────────────────────────┐    │   │  │  │
│  │  │   │  │  REGULATORY-SERVICE                                             │    │   │  │  │
│  │  │   │  │  └── server2:regulatory:8090 (172.18.0.3:8090) [REMOTE]         │    │   │  │  │
│  │  │   │  └─────────────────────────────────────────────────────────────────┘    │   │  │  │
│  │  │   │                                                                         │   │  │  │
│  │  │   │  Last Refresh: 2024-01-01 10:00:30                                      │   │  │  │
│  │  │   │  Apps Hash: 8f3a2b1c                                                    │   │  │  │
│  │  │   │                                                                         │   │  │  │
│  │  │   └─────────────────────────────────────────────────────────────────────────┘   │  │  │
│  │  │                                                                                 │  │  │
│  │  └─────────────────────────────────────────────────────────────────────────────────┘  │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Cache Refresh Timeline                                                               │  │
│  │  ──────────────────────                                                               │  │
│  │                                                                                       │  │
│  │  Time ────────────────────────────────────────────────────────────────────────────►   │  │
│  │                                                                                       │  │
│  │  0s         30s        60s        90s        120s                                     │  │
│  │  │          │          │          │          │                                        │  │
│  │  ▼          ▼          ▼          ▼          ▼                                        │  │
│  │  ┌──┐       ┌─┐        ┌─┐        ┌─┐        ┌─┐                                      │  │
│  │  │FF│       │D│        │D│        │D│        │D│                                      │  │
│  │  └──┘       └─┘        └─┘        └─┘        └─┘                                      │  │
│  │                                                                                       │  │
│  │  FF = Full Fetch (on startup)                                                         │  │
│  │  D  = Delta Fetch (incremental)                                                       │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Service Call Flow with Load Balancing

How a service call is made using discovered instances and local-first load balancing.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                     SERVICE CALL FLOW WITH LOAD BALANCING                                   │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Step 1: Define Feign Client                                                          │  │
│  │  ───────────────────────────                                                          │  │
│  │                                                                                       │  │
│  │  @FeignClient(name = "REFDATA-SERVICE")                                              │  │
│  │  public interface RefDataClient {                                                    │  │
│  │                                                                                       │  │
│  │      @GetMapping("/api/v1/refdata/{type}")                                           │  │
│  │      RefDataResponse getRefData(@PathVariable String type);                          │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  │  // Note: "REFDATA-SERVICE" matches the app name in Eureka registry                  │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Step 2: Service Call Execution                                                       │  │
│  │  ──────────────────────────────                                                       │  │
│  │                                                                                       │  │
│  │  @Service                                                                            │  │
│  │  public class IngestionService {                                                     │  │
│  │                                                                                       │  │
│  │      @Autowired                                                                      │  │
│  │      private RefDataClient refDataClient;                                            │  │
│  │                                                                                       │  │
│  │      public void processData(IngestRequest request) {                                │  │
│  │          // This triggers the entire load balancing flow                             │  │
│  │          RefDataResponse refData = refDataClient.getRefData("currency");             │  │
│  │      }                                                                                │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘

                                          │
                                          ▼

┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                     INTERNAL CALL FLOW (What happens behind the scenes)                     │
│                                                                                             │
│                                                                                             │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌───────────┐  │
│  │  Feign      │    │  Load       │    │  Service    │    │  Local-First│    │  HTTP     │  │
│  │  Client     │───►│  Balancer   │───►│  Instance   │───►│  Selector   │───►│  Request  │  │
│  │             │    │  Interceptor│    │  List       │    │             │    │           │  │
│  └─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘    └───────────┘  │
│                                                                                             │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │                                                                                       │  │
│  │  1. FeignClient Invocation                                                            │  │
│  │     ─────────────────────────                                                         │  │
│  │     refDataClient.getRefData("currency")                                              │  │
│  │                          │                                                            │  │
│  │                          ▼                                                            │  │
│  │  2. LoadBalancerFeignClient                                                           │  │
│  │     ───────────────────────────                                                       │  │
│  │     Intercepts the call, extracts service name "REFDATA-SERVICE"                      │  │
│  │                          │                                                            │  │
│  │                          ▼                                                            │  │
│  │  3. ReactiveLoadBalancer.choose(serviceId)                                            │  │
│  │     ──────────────────────────────────────                                            │  │
│  │     LocalFirstLoadBalancer.choose("REFDATA-SERVICE")                                  │  │
│  │                          │                                                            │  │
│  │                          ▼                                                            │  │
│  │  4. ServiceInstanceListSupplier.get()                                                 │  │
│  │     ─────────────────────────────────                                                 │  │
│  │     Retrieves instances from local cache:                                             │  │
│  │     [                                                                                 │  │
│  │       { host: "server1", port: 8081, metadata: { server-id: "server1" } },            │  │
│  │       { host: "server3", port: 8081, metadata: { server-id: "server3" } }             │  │
│  │     ]                                                                                 │  │
│  │                          │                                                            │  │
│  │                          ▼                                                            │  │
│  │  5. Local-First Selection Algorithm                                                   │  │
│  │     ──────────────────────────────────                                                │  │
│  │     String currentServer = System.getenv("SERVER_ID");  // "server1"                  │  │
│  │                                                                                       │  │
│  │     // Find local instance                                                            │  │
│  │     ServiceInstance local = instances.stream()                                        │  │
│  │         .filter(i -> i.getMetadata().get("server-id").equals(currentServer))          │  │
│  │         .findFirst()                                                                  │  │
│  │         .orElse(null);                                                                │  │
│  │                                                                                       │  │
│  │     if (local != null && local.isHealthy()) {                                         │  │
│  │         return local;  // ✓ Return local instance                                     │  │
│  │     }                                                                                 │  │
│  │                                                                                       │  │
│  │     // Fallback: select by load                                                       │  │
│  │     return selectByLowestLoad(instances);                                             │  │
│  │                          │                                                            │  │
│  │                          ▼                                                            │  │
│  │  6. HTTP Request Execution                                                            │  │
│  │     ─────────────────────────                                                         │  │
│  │     GET http://server1:8081/api/v1/refdata/currency                                   │  │
│  │     (Local call - no network hop)                                                     │  │
│  │                          │                                                            │  │
│  │                          ▼                                                            │  │
│  │  7. Response                                                                          │  │
│  │     ────────                                                                          │  │
│  │     RefDataResponse { ... }                                                           │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Retry Mechanism with Service Discovery

How retry logic works with the service registry when calls fail.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                     RETRY MECHANISM WITH SERVICE DISCOVERY                                  │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Resilience4j Configuration                                                           │  │
│  │  ──────────────────────────                                                           │  │
│  │                                                                                       │  │
│  │  resilience4j:                                                                        │  │
│  │    retry:                                                                             │  │
│  │      instances:                                                                       │  │
│  │        refdata-service:                                                               │  │
│  │          max-attempts: 3                                                              │  │
│  │          wait-duration: 500ms                                                         │  │
│  │          exponential-backoff-multiplier: 2                                            │  │
│  │          retry-exceptions:                                                            │  │
│  │            - java.io.IOException                                                      │  │
│  │            - java.net.ConnectException                                                │  │
│  │            - java.util.concurrent.TimeoutException                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Retry Flow When Local Instance Fails                                                 │  │
│  │  ────────────────────────────────────                                                 │  │
│  │                                                                                       │  │
│  │                                                                                       │  │
│  │   Attempt 1 (Local)              Attempt 2 (Remote)           Attempt 3 (Remote)      │  │
│  │   ─────────────────              ──────────────────           ─────────────────       │  │
│  │                                                                                       │  │
│  │   ┌─────────────────┐            ┌─────────────────┐          ┌─────────────────┐     │  │
│  │   │ server1:8081    │            │ server3:8081    │          │ server3:8081    │     │  │
│  │   │ (LOCAL)         │            │ (REMOTE)        │          │ (REMOTE)        │     │  │
│  │   └────────┬────────┘            └────────┬────────┘          └────────┬────────┘     │  │
│  │            │                              │                            │              │  │
│  │            ▼                              ▼                            ▼              │  │
│  │       ╔═══════╗                      ╔═══════╗                    ╔═══════╗           │  │
│  │       ║ FAIL  ║                      ║ FAIL  ║                    ║SUCCESS║           │  │
│  │       ║Timeout║                      ║ConnErr║                    ║  200  ║           │  │
│  │       ╚═══════╝                      ╚═══════╝                    ╚═══════╝           │  │
│  │            │                              │                            │              │  │
│  │            │   Wait 500ms                 │   Wait 1000ms              │              │  │
│  │            │   ──────────                 │   ───────────              │              │  │
│  │            ▼                              ▼                            ▼              │  │
│  │       Mark server1                   Mark server3                 Return              │  │
│  │       unhealthy                      unhealthy                    Response            │  │
│  │       (temporary)                    (temporary)                                      │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Retry Implementation Code Flow

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                             │
│                     RETRY IMPLEMENTATION CODE FLOW                                          │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  FeignClient with Retry Decorator                                                     │  │
│  │  ────────────────────────────────                                                     │  │
│  │                                                                                       │  │
│  │  @FeignClient(name = "REFDATA-SERVICE", configuration = FeignConfig.class)            │  │
│  │  public interface RefDataClient {                                                     │  │
│  │      @GetMapping("/api/v1/refdata/{type}")                                            │  │
│  │      RefDataResponse getRefData(@PathVariable String type);                           │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  │  @Configuration                                                                       │  │
│  │  public class FeignConfig {                                                           │  │
│  │                                                                                       │  │
│  │      @Bean                                                                            │  │
│  │      public Retryer retryer() {                                                       │  │
│  │          // Disable Feign's built-in retry (use Resilience4j instead)                 │  │
│  │          return Retryer.NEVER_RETRY;                                                  │  │
│  │      }                                                                                │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Service Layer with Resilience4j Retry                                                │  │
│  │  ─────────────────────────────────────                                                │  │
│  │                                                                                       │  │
│  │  @Service                                                                             │  │
│  │  public class IngestionService {                                                      │  │
│  │                                                                                       │  │
│  │      private final RefDataClient refDataClient;                                       │  │
│  │      private final Retry retry;                                                       │  │
│  │      private final ServiceInstanceRegistry instanceRegistry;                          │  │
│  │                                                                                       │  │
│  │      public IngestionService(                                                         │  │
│  │              RefDataClient refDataClient,                                             │  │
│  │              RetryRegistry retryRegistry,                                             │  │
│  │              ServiceInstanceRegistry instanceRegistry) {                              │  │
│  │                                                                                       │  │
│  │          this.refDataClient = refDataClient;                                          │  │
│  │          this.retry = retryRegistry.retry("refdata-service");                         │  │
│  │          this.instanceRegistry = instanceRegistry;                                    │  │
│  │                                                                                       │  │
│  │          // Add event listener for retry events                                       │  │
│  │          retry.getEventPublisher()                                                    │  │
│  │              .onRetry(event -> handleRetryEvent(event));                              │  │
│  │      }                                                                                │  │
│  │                                                                                       │  │
│  │      public RefDataResponse getRefDataWithRetry(String type) {                        │  │
│  │          Supplier<RefDataResponse> supplier = () -> refDataClient.getRefData(type);   │  │
│  │                                                                                       │  │
│  │          // Wrap with retry                                                           │  │
│  │          Supplier<RefDataResponse> retryingSupplier =                                 │  │
│  │              Retry.decorateSupplier(retry, supplier);                                 │  │
│  │                                                                                       │  │
│  │          try {                                                                        │  │
│  │              return retryingSupplier.get();                                           │  │
│  │          } catch (Exception e) {                                                      │  │
│  │              // All retries exhausted                                                 │  │
│  │              throw new ServiceUnavailableException("RefData service unavailable", e); │  │
│  │          }                                                                            │  │
│  │      }                                                                                │  │
│  │                                                                                       │  │
│  │      private void handleRetryEvent(RetryOnRetryEvent event) {                         │  │
│  │          // On retry, mark the failed instance as potentially unhealthy               │  │
│  │          String lastAttemptedHost = getLastAttemptedHost();                           │  │
│  │          instanceRegistry.markInstanceUnhealthy(                                      │  │
│  │              "REFDATA-SERVICE",                                                       │  │
│  │              lastAttemptedHost,                                                       │  │
│  │              Duration.ofSeconds(30)  // Temporary unhealthy mark                      │  │
│  │          );                                                                           │  │
│  │                                                                                       │  │
│  │          logger.warn("Retry attempt {} for RefData service. "                         │  │
│  │              + "Marked {} as unhealthy. Reason: {}",                                  │  │
│  │              event.getNumberOfRetryAttempts(),                                        │  │
│  │              lastAttemptedHost,                                                       │  │
│  │              event.getLastThrowable().getMessage());                                  │  │
│  │      }                                                                                │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────────────────┐  │
│  │  Custom Load Balancer - Instance Selection on Retry                                   │  │
│  │  ──────────────────────────────────────────────────                                   │  │
│  │                                                                                       │  │
│  │  @Component                                                                           │  │
│  │  public class LocalFirstLoadBalancer implements ReactorServiceInstanceLoadBalancer {  │  │
│  │                                                                                       │  │
│  │      private final ServiceInstanceRegistry registry;                                  │  │
│  │      private final String currentServerId;                                            │  │
│  │      private final AtomicReference<String> lastFailedInstance = new AtomicReference<>();
│  │                                                                                       │  │
│  │      @Override                                                                        │  │
│  │      public Mono<Response<ServiceInstance>> choose(Request request) {                 │  │
│  │                                                                                       │  │
│  │          List<ServiceInstance> instances = registry.getHealthyInstances(serviceId);   │  │
│  │                                                                                       │  │
│  │          if (instances.isEmpty()) {                                                   │  │
│  │              return Mono.just(new EmptyResponse());                                   │  │
│  │          }                                                                            │  │
│  │                                                                                       │  │
│  │          // Remove last failed instance from candidates (for retry)                   │  │
│  │          String lastFailed = lastFailedInstance.get();                                │  │
│  │          if (lastFailed != null) {                                                    │  │
│  │              instances = instances.stream()                                           │  │
│  │                  .filter(i -> !i.getInstanceId().equals(lastFailed))                  │  │
│  │                  .collect(Collectors.toList());                                       │  │
│  │          }                                                                            │  │
│  │                                                                                       │  │
│  │          // 1. Try local instance first                                               │  │
│  │          Optional<ServiceInstance> local = instances.stream()                         │  │
│  │              .filter(i -> i.getMetadata().get("server-id").equals(currentServerId))   │  │
│  │              .findFirst();                                                            │  │
│  │                                                                                       │  │
│  │          if (local.isPresent()) {                                                     │  │
│  │              return Mono.just(new DefaultResponse(local.get()));                      │  │
│  │          }                                                                            │  │
│  │                                                                                       │  │
│  │          // 2. Select remote instance with lowest load                                │  │
│  │          ServiceInstance selected = instances.stream()                                │  │
│  │              .min(Comparator.comparing(i -> registry.getLoad(i)))                     │  │
│  │              .orElse(instances.get(0));                                               │  │
│  │                                                                                       │  │
│  │          return Mono.just(new DefaultResponse(selected));                             │  │
│  │      }                                                                                │  │
│  │                                                                                       │  │
│  │      public void markLastFailed(String instanceId) {                                  │  │
│  │          lastFailedInstance.set(instanceId);                                          │  │
│  │      }                                                                                │  │
│  │                                                                                       │  │
│  │      public void clearLastFailed() {                                                  │  │
│  │          lastFailedInstance.set(null);                                                │  │
│  │      }                                                                                │  │
│  │  }                                                                                    │  │
│  │                                                                                       │  │
│  └───────────────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Complete End-to-End Flow

Comprehensive flow showing all components working together.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                                                                                     │
│                              COMPLETE END-TO-END FLOW                                               │
│                                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                                                                                             │    │
│  │   PHASE 1: STARTUP & REGISTRATION                                                           │    │
│  │   ────────────────────────────────                                                          │    │
│  │                                                                                             │    │
│  │   ┌───────────────┐          ┌───────────────┐          ┌───────────────┐                   │    │
│  │   │ RefData       │          │ Eligibility   │          │ Eureka        │                   │    │
│  │   │ Service       │          │ Service       │          │ Server        │                   │    │
│  │   │ (server1)     │          │ (server1)     │          │ (server1)     │                   │    │
│  │   └───────┬───────┘          └───────┬───────┘          └───────┬───────┘                   │    │
│  │           │                          │                          │                           │    │
│  │           │ 1. Register              │ 2. Register              │                           │    │
│  │           │─────────────────────────────────────────────────────►                           │    │
│  │           │                          │─────────────────────────►│                           │    │
│  │           │                          │                          │                           │    │
│  │           │ 3. Heartbeats (every 30s)│                          │                           │    │
│  │           │─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─►│                            │    │
│  │           │                          │─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ►│                            │    │
│  │                                                                                             │    │
│  └─────────────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                                                                                             │    │
│  │   PHASE 2: SERVICE CALL (Happy Path - Local Instance Available)                             │    │
│  │   ─────────────────────────────────────────────────────────────                             │    │
│  │                                                                                             │    │
│  │   ┌───────────────┐    ┌───────────────┐    ┌───────────────┐    ┌───────────────┐          │    │
│  │   │ Client        │    │ Ingestion     │    │ LocalFirst    │    │ RefData       │          │    │
│  │   │ Request       │    │ Service       │    │ LoadBalancer  │    │ Service       │          │    │
│  │   │               │    │ (server1)     │    │               │    │ (server1)     │          │    │
│  │   └───────┬───────┘    └───────┬───────┘    └───────┬───────┘    └───────┬───────┘          │    │
│  │           │                    │                    │                    │                  │    │
│  │           │ 1. POST /ingest    │                    │                    │                  │    │
│  │           │───────────────────►│                    │                    │                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │ 2. getRefData()    │                    │                  │    │
│  │           │                    │───────────────────►│                    │                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │                    │ 3. Get instances   │                  │    │
│  │           │                    │                    │    from local cache│                  │    │
│  │           │                    │                    │    [server1, server3]                 │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │                    │ 4. Select LOCAL    │                  │    │
│  │           │                    │                    │    (server1:8081)  │                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │                    │ 5. HTTP GET        │                  │    │
│  │           │                    │                    │───────────────────►│                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │                    │ 6. Response        │                  │    │
│  │           │                    │                    │◄───────────────────│                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │ 7. RefData Response│                    │                  │    │
│  │           │                    │◄───────────────────│                    │                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │ 8. Ingest Response │                    │                    │                  │    │
│  │           │◄───────────────────│                    │                    │                  │    │
│  │                                                                                             │    │
│  └─────────────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                                                                                             │    │
│  │   PHASE 3: SERVICE CALL WITH RETRY (Local Instance Fails)                                   │    │
│  │   ───────────────────────────────────────────────────────                                   │    │
│  │                                                                                             │    │
│  │   ┌───────────────┐    ┌───────────────┐    ┌───────────────┐    ┌───────────────┐          │    │
│  │   │ Ingestion     │    │ Resilience4j  │    │ LocalFirst    │    │ RefData       │          │    │
│  │   │ Service       │    │ Retry         │    │ LoadBalancer  │    │ Services      │          │    │
│  │   │ (server1)     │    │               │    │               │    │               │          │    │
│  │   └───────┬───────┘    └───────┬───────┘    └───────┬───────┘    └───────┬───────┘          │    │
│  │           │                    │                    │                    │                  │    │
│  │           │ 1. getRefData()    │                    │                    │                  │    │
│  │           │───────────────────►│                    │                    │                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │ 2. Execute         │                    │                  │    │
│  │           │                    │    (Attempt 1)     │                    │                  │    │
│  │           │                    │───────────────────►│                    │                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │                    │ 3. Select LOCAL    │                  │    │
│  │           │                    │                    │    server1:8081    │                  │    │
│  │           │                    │                    │───────────────────►│ (server1)        │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │                    │ 4. TIMEOUT!        │                  │    │
│  │           │                    │                    │◄ ─ ─ ─ ─ ─ ─ ─ ─ ─ │                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │ 5. Catch exception │                    │                  │    │
│  │           │                    │    Mark server1    │                    │                  │    │
│  │           │                    │    as unhealthy    │                    │                  │    │
│  │           │                    │◄───────────────────│                    │                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │ 6. Wait 500ms      │                    │                  │    │
│  │           │                    │    (exponential    │                    │                  │    │
│  │           │                    │     backoff)       │                    │                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │ 7. Execute         │                    │                  │    │
│  │           │                    │    (Attempt 2)     │                    │                  │    │
│  │           │                    │───────────────────►│                    │                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │                    │ 8. Select REMOTE   │                  │    │
│  │           │                    │                    │    server3:8081    │                  │    │
│  │           │                    │                    │    (server1 marked │                  │    │
│  │           │                    │                    │     unhealthy)     │                  │    │
│  │           │                    │                    │───────────────────►│ (server3)        │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │                    │ 9. SUCCESS (200)   │                  │    │
│  │           │                    │                    │◄───────────────────│                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │                    │ 10. Clear retry    │                    │                  │    │
│  │           │                    │     state          │                    │                  │    │
│  │           │                    │◄───────────────────│                    │                  │    │
│  │           │                    │                    │                    │                  │    │
│  │           │ 11. Response       │                    │                    │                  │    │
│  │           │◄───────────────────│                    │                    │                  │    │
│  │                                                                                             │    │
│  └─────────────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                                                                                             │    │
│  │   PHASE 4: BACKGROUND HEALTH RECOVERY                                                       │    │
│  │   ───────────────────────────────────                                                       │    │
│  │                                                                                             │    │
│  │   ┌───────────────┐    ┌───────────────┐    ┌───────────────┐                               │    │
│  │   │ Health        │    │ Service       │    │ RefData       │                               │    │
│  │   │ Monitor       │    │ Instance      │    │ Service       │                               │    │
│  │   │ (Executor)    │    │ Registry      │    │ (server1)     │                               │    │
│  │   └───────┬───────┘    └───────┬───────┘    └───────┬───────┘                               │    │
│  │           │                    │                    │                                       │    │
│  │           │ 1. Scheduled check │                    │                                       │    │
│  │           │    (every 10s)     │                    │                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │ 2. GET /actuator/health                 │                                       │    │
│  │           │─────────────────────────────────────────►                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │ 3. { "status": "UP" }                   │                                       │    │
│  │           │◄─────────────────────────────────────────                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │ 4. Update registry │                    │                                       │    │
│  │           │───────────────────►│                    │                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │                    │ 5. Mark server1    │                                       │    │
│  │           │                    │    as HEALTHY      │                                       │    │
│  │           │                    │    again           │                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │                    │                    │                                       │    │
│  │   ───── Future calls can now use server1 (LOCAL) again ─────                                │    │
│  │                                                                                             │    │
│  └─────────────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────────────────────┐    │
│  │                                                                                             │    │
│  │   PHASE 5: GRACEFUL SHUTDOWN & DEREGISTRATION                                               │    │
│  │   ───────────────────────────────────────────                                               │    │
│  │                                                                                             │    │
│  │   ┌───────────────┐    ┌───────────────┐    ┌───────────────┐                               │    │
│  │   │ RefData       │    │ Eureka        │    │ Ingestion     │                               │    │
│  │   │ Service       │    │ Server        │    │ Service       │                               │    │
│  │   │ (server1)     │    │               │    │ (Local Cache) │                               │    │
│  │   └───────┬───────┘    └───────┬───────┘    └───────┬───────┘                               │    │
│  │           │                    │                    │                                       │    │
│  │           │ 1. SIGTERM         │                    │                                       │    │
│  │           │    received        │                    │                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │ 2. DELETE /eureka/apps/REFDATA-SERVICE/server1:refdata:8081                     │    │
│  │           │───────────────────►│                    │                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │                    │ 3. Remove from     │                                       │    │
│  │           │                    │    registry        │                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │                    │ 4. Invalidate      │                                       │    │
│  │           │                    │    cache           │                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │ 5. 200 OK          │                    │                                       │    │
│  │           │◄───────────────────│                    │                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │ 6. Shutdown        │                    │                                       │    │
│  │           │    complete        │                    │                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │                    │ 7. Delta fetch     │                                       │    │
│  │           │                    │    (next 30s)      │                                       │    │
│  │           │                    │◄───────────────────│                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │                    │ 8. Delta includes  │                                       │    │
│  │           │                    │    DELETED action  │                                       │    │
│  │           │                    │───────────────────►│                                       │    │
│  │           │                    │                    │                                       │    │
│  │           │                    │                    │ 9. Remove server1                     │    │
│  │           │                    │                    │    from local cache                   │    │
│  │           │                    │                    │                                       │    │
│  │   ───── Future calls will only go to server3 (REMOTE) ─────                                 │    │
│  │                                                                                             │    │
│  └─────────────────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Summary

| Operation | Endpoint | Frequency | Purpose |
|-----------|----------|-----------|---------|
| **Register** | `POST /eureka/apps/{app}` | On startup | Add instance to registry |
| **Heartbeat** | `PUT /eureka/apps/{app}/{id}` | Every 30s | Maintain lease |
| **Fetch** | `GET /eureka/apps` | Every 30s | Get instance list |
| **Delta Fetch** | `GET /eureka/apps/delta` | Every 30s | Get changes only |
| **Deregister** | `DELETE /eureka/apps/{app}/{id}` | On shutdown | Remove from registry |

### Key Concepts

1. **Client-Side Caching**: Services cache the registry locally to avoid network calls for every service lookup
2. **Local-First Routing**: Prefer local instances to minimize network latency
3. **Retry with Instance Rotation**: On failure, mark instance unhealthy and try different instance
4. **Background Health Recovery**: Periodically check unhealthy instances to restore them
5. **Graceful Degradation**: System continues working even when some instances fail
