package io.floci.oci.services.oke;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.CompartmentUsage;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.Etags;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.Ocids;
import io.floci.oci.core.common.Resettable;
import io.floci.oci.core.common.ServiceDescriptor;
import io.floci.oci.core.common.ServiceRegistry;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.StorageFactory;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.IdentityService;
import io.floci.oci.services.oke.model.StoredNode;
import io.floci.oci.services.oke.model.StoredNodePool;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Business logic service for OCI Container Engine for Kubernetes (OKE) API emulation.
 */
@ApplicationScoped
public class OkeService implements Resettable, CompartmentUsage {

    private static final Logger LOG = Logger.getLogger(OkeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int API_TOKEN_BYTES = 32;
    private static final long READINESS_POLL_SECONDS = 3;
    /** The endpoints a cluster without a k3s sidecar (mock mode) advertises. */
    static final Map<String, String> MOCK_ENDPOINTS = Map.of(
            "kubernetes", "https://127.0.0.1:6443",
            "privateEndpoint", "10.0.0.10:6443");

    /**
     * A real-mode cluster still CREATING or UPDATING, with the tenancy that owns it, when its k3s
     * server started and the work request that finishes with it.
     */
    record PendingCluster(String tenancyId, Instant since, String workRequestId) {}

    /** A stored cluster together with the tenancy whose partition it lives in. */
    private record OwnedCluster(String tenancyId, StoredOkeCluster cluster) {}

    private final StorageBackend<String, StoredOkeCluster> clusters;
    private final StorageBackend<String, StoredNodePool> nodePools;
    private final EmulatorConfig config;
    private final OciContext ociContext;
    private final ServiceRegistry serviceRegistry;
    private final WorkRequestService workRequests;
    private final OkeClusterManager clusterManager;
    private final IdentityService identity;
    private final Supplier<String> tenancyId;
    private final Clock clock;
    private final Map<String, PendingCluster> pendingClusters = new ConcurrentHashMap<>();
    /** Clusters whose agents the poller still has to bring in line with their node pools, by owning tenancy. */
    private final Map<String, String> reconcileQueue = new ConcurrentHashMap<>();
    /** Nodes whose agent joined and got OKE's post-join labels in this process. */
    private final Set<String> finishedNodes = ConcurrentHashMap.newKeySet();
    private final Map<String, String> capWarnings = new ConcurrentHashMap<>();
    private final Map<String, Instant> poolsPendingSince = new ConcurrentHashMap<>();
    private final ScheduledExecutorService readinessPoller;

    @Inject
    public OkeService(StorageFactory storageFactory, EmulatorConfig config,
                      ServiceRegistry serviceRegistry, WorkRequestService workRequests,
                      OkeClusterManager clusterManager, OciContext ociContext, IdentityService identity) {
        this(
            storageFactory.create("oke", "oke-clusters.json",
                new TypeReference<Map<String, StoredOkeCluster>>() {}),
            storageFactory.create("oke", "oke-nodepools.json",
                new TypeReference<Map<String, StoredNodePool>>() {}),
            config,
            serviceRegistry,
            workRequests,
            clusterManager,
            ociContext::tenancyId,
            Clock.systemUTC(),
            ociContext,
            identity
        );
    }

    OkeService(StorageBackend<String, StoredOkeCluster> clusters,
               StorageBackend<String, StoredNodePool> nodePools,
               EmulatorConfig config,
               ServiceRegistry serviceRegistry,
               WorkRequestService workRequests,
               OkeClusterManager clusterManager) {
        this(clusters, nodePools, config, serviceRegistry, workRequests, clusterManager,
                () -> config != null ? config.defaultTenancyId() : null, Clock.systemUTC(),
                OciContext.fromConfig(config), null);
    }

    OkeService(StorageBackend<String, StoredOkeCluster> clusters,
               StorageBackend<String, StoredNodePool> nodePools,
               EmulatorConfig config,
               ServiceRegistry serviceRegistry,
               WorkRequestService workRequests,
               OkeClusterManager clusterManager,
               Supplier<String> tenancyId,
               Clock clock,
               OciContext ociContext,
               IdentityService identity) {
        this.ociContext = ociContext;
        this.identity = identity;
        this.clusters = clusters;
        this.nodePools = nodePools;
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.workRequests = workRequests;
        this.clusterManager = clusterManager;
        this.tenancyId = tenancyId;
        this.clock = clock;
        // No thread starts until the first task is scheduled, which only real mode does.
        this.readinessPoller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "oke-readiness");
            thread.setDaemon(true);
            return thread;
        });
    }

    void onStart(@Observes StartupEvent ev) {
        if (config.services().oke().enabled()) {
            OkeKubeconfigGenerator.validateAuthMode(config.services().oke().kubeconfigAuth());
            OkeClusterManager.validateEndpointMode(config.services().oke().endpointMode());
        }
        if (serviceRegistry != null) {
            serviceRegistry.register(ServiceDescriptor.builder("oke")
                    .enabled(config.services().oke().enabled())
                    .storageKey("oke")
                    .resourceClasses(OkeController.class, OkeTokenWebhookController.class)
                    .build());
        }
        reconstructClusterState();
        if (realMode() && config.services().oke().enabled()) {
            startReadinessPoller();
        }
    }

    @PreDestroy
    void stopReadinessPoller() {
        readinessPoller.shutdownNow();
    }

    void reconstructClusterState() {
        List<OwnedCluster> existingClusters = backfillInternalFields();
        backfillNodePools();
        if (!realMode()) {
            return;
        }
        for (OwnedCluster owned : existingClusters) {
            StoredOkeCluster cluster = owned.cluster();
            if ("FAILED".equals(cluster.getLifecycleState())) {
                continue;
            }
            try {
                clusterManager.registerExistingCluster(cluster);
            } catch (RuntimeException e) {
                LOG.errorv(e, "Could not restore the k3s server of OKE cluster {0}", cluster.getId());
                cluster.setLifecycleState("FAILED");
                cluster.setLifecycleDetails("The k3s server did not start after a restart: " + e.getMessage());
                putCluster(owned.tenancyId(), cluster);
                continue;
            }
            requestReconcile(cluster);
            if (cluster.getCaCertificate() == null || clusterManager.prunesVolumesOnStop()) {
                // Shutdown removed the volume holding the k3s CA, so the new sidecar has a new one.
                cluster.setCaCertificate(null);
                cluster.setLifecycleState("CREATING");
                pendingClusters.put(cluster.getId(), new PendingCluster(owned.tenancyId(), clock.instant(),
                        cluster.getCreateWorkRequestId()));
            }
            putCluster(owned.tenancyId(), cluster);
        }
    }

    /**
     * Fills the internal fields that clusters stored by an older floci-oci lack (API token,
     * tenancy, region) and persists the ones it changed, so the k3s token file, the webhook scope
     * and {@code CreateKubeconfig} agree after a restart.
     */
    private List<OwnedCluster> backfillInternalFields() {
        List<OwnedCluster> existingClusters = new ArrayList<>();
        // A store that is not tenancy-aware (unit tests) is one partition, named null: the
        // request tenancy in the default region.
        Map<String, Map<String, StoredOkeCluster>> partitions =
                TenancyAwareStorageBackend.entriesByPartition(clusters, null);
        for (Map.Entry<String, Map<String, StoredOkeCluster>> partition : partitions.entrySet()) {
            String name = partition.getKey();
            String owner = name == null ? tenancyId.get() : TenancyAwareStorageBackend.tenancyOf(name);
            String region = name == null
                    ? defaultRegion()
                    : TenancyAwareStorageBackend.regionOf(name).orElse(defaultRegion());
            for (StoredOkeCluster cluster : partition.getValue().values()) {
                if (backfill(cluster, owner, region)) {
                    TenancyAwareStorageBackend.putIn(clusters, partition.getKey(), cluster.getId(), cluster);
                }
                existingClusters.add(new OwnedCluster(owner, cluster));
            }
        }
        return existingClusters;
    }

    /**
     * Gives node pools stored by an older floci-oci the tenancy and region of the partition they
     * live in, so the poller can write them back outside a request.
     */
    private void backfillNodePools() {
        Map<String, Map<String, StoredNodePool>> partitions =
                TenancyAwareStorageBackend.entriesByPartition(nodePools, null);
        for (Map.Entry<String, Map<String, StoredNodePool>> partition : partitions.entrySet()) {
            String name = partition.getKey();
            String owner = name == null ? tenancyId.get() : TenancyAwareStorageBackend.tenancyOf(name);
            String region = name == null
                    ? defaultRegion()
                    : TenancyAwareStorageBackend.regionOf(name).orElse(defaultRegion());
            for (StoredNodePool pool : partition.getValue().values()) {
                if (pool.getTenancyId() == null || pool.getRegion() == null) {
                    pool.setTenancyId(owner);
                    pool.setRegion(region);
                    TenancyAwareStorageBackend.putIn(nodePools, partition.getKey(), pool.getId(), pool);
                }
            }
        }
    }

    private boolean backfill(StoredOkeCluster cluster, String owner, String region) {
        boolean changed = false;
        if (cluster.getApiToken() == null) {
            cluster.setApiToken(newApiToken());
            changed = true;
        }
        if (cluster.getTenancyId() == null && owner != null) {
            cluster.setTenancyId(owner);
            changed = true;
        }
        if (cluster.getRegion() == null && region != null) {
            cluster.setRegion(region);
            changed = true;
        }
        // Clusters stored before GetCluster returned an etag, so their first update can use if-match.
        if (cluster.getEtag() == null) {
            cluster.setEtag(Etags.newEtag());
            changed = true;
        }
        return changed;
    }

    private String defaultRegion() {
        return config != null ? config.defaultRegion() : null;
    }

    private void startReadinessPoller() {
        readinessPoller.scheduleWithFixedDelay(this::pollReadinessSafely,
                READINESS_POLL_SECONDS, READINESS_POLL_SECONDS, TimeUnit.SECONDS);
    }

    private void pollReadinessSafely() {
        try {
            pollReadiness();
        } catch (RuntimeException e) {
            LOG.warnv(e, "OKE readiness poll failed");
        }
    }

    /**
     * One pass over the CREATING clusters: ACTIVE once the k3s API answers (capturing its CA),
     * FAILED when the sidecar has exited or the ready timeout has passed. Each outcome also
     * finishes the CLUSTER_CREATE work request, which is what Terraform and the CLI wait on.
     */
    void pollReadiness() {
        for (Map.Entry<String, PendingCluster> entry : pendingClusters.entrySet()) {
            String clusterId = entry.getKey();
            PendingCluster pending = entry.getValue();
            Optional<StoredOkeCluster> found = findCluster(pending.tenancyId(), clusterId);
            if (found.isEmpty() || !isStarting(found.get())) {
                LOG.debugv("OKE cluster {0} is no longer CREATING or UPDATING in tenancy {1}; stop polling it",
                        clusterId, pending.tenancyId());
                pendingClusters.remove(clusterId);
                continue;
            }
            try {
                pollOne(pending, found.get());
            } catch (RuntimeException e) {
                LOG.warnv(e, "OKE readiness poll failed for cluster {0}", clusterId);
            }
        }
        reconcileQueued();
    }

    private static boolean isStarting(StoredOkeCluster cluster) {
        return "CREATING".equals(cluster.getLifecycleState()) || "UPDATING".equals(cluster.getLifecycleState());
    }

    private void pollOne(PendingCluster pending, StoredOkeCluster cluster) {
        Optional<String> caCertificate = clusterManager.probeReady(cluster);
        if (caCertificate.isPresent()) {
            cluster.setCaCertificate(caCertificate.get());
            finishCreate(pending, cluster, "ACTIVE", "SUCCEEDED", null);
        } else if (!clusterManager.isClusterRunning(cluster)) {
            finishCreate(pending, cluster, "FAILED", "FAILED",
                    "The k3s sidecar exited before its API server became ready");
        } else if (clock.instant().isAfter(
                pending.since().plusSeconds(config.services().oke().readyTimeoutSeconds()))) {
            finishCreate(pending, cluster, "FAILED", "FAILED",
                    "The k3s API server was not ready after "
                            + config.services().oke().readyTimeoutSeconds() + " seconds");
        }
    }

    /**
     * Records the outcome only while the cluster is still pending, atomically with dropping it
     * from {@link #pendingClusters}: a delete or reset that removed it during the probe wins, so
     * the poller never writes back a cluster that is gone.
     */
    private void finishCreate(PendingCluster started, StoredOkeCluster cluster, String lifecycleState,
                              String workRequestStatus, String details) {
        String owner = started.tenancyId();
        AtomicBoolean recorded = new AtomicBoolean();
        pendingClusters.computeIfPresent(cluster.getId(), (id, pending) -> {
            cluster.setLifecycleState(lifecycleState);
            cluster.setLifecycleDetails(details);
            // A new server may sit on a new volume, with a new join token.
            cluster.setJoinToken(null);
            putCluster(owner, cluster);
            if (pending.workRequestId() != null) {
                // Terraform builds its failure message from the work request's errors.
                List<StoredWorkRequest.ErrorEntry> errors = "FAILED".equals(workRequestStatus) && details != null
                        ? List.of(new StoredWorkRequest.ErrorEntry("InternalServerError", details))
                        : List.of();
                workRequests.finish(owner, cluster.getRegion(),
                        pending.workRequestId(), workRequestStatus, errors);
            }
            recorded.set(true);
            return null;
        });
        if (!recorded.get()) {
            LOG.debugv("OKE cluster {0} left pending before it became {1}", cluster.getId(), lifecycleState);
            return;
        }
        LOG.infov("OKE cluster {0} is {1}", cluster.getId(), lifecycleState);
        if ("ACTIVE".equals(lifecycleState)) {
            requestReconcile(cluster);
        }
    }

    /**
     * Looks a cluster up in an explicit tenancy, for callers outside a request scope, across the
     * tenancy's region partitions: a cluster's OCID region code cannot name its region when a
     * custom default region shares that code.
     */
    Optional<StoredOkeCluster> findCluster(String owner, String clusterId) {
        return TenancyAwareStorageBackend.findInTenancy(clusters, owner, clusterId);
    }

    /** Stores a cluster in the partition of the tenancy and region it was created in. */
    private void putCluster(String owner, StoredOkeCluster cluster) {
        TenancyAwareStorageBackend.putIn(clusters,
                TenancyAwareStorageBackend.regionalPartition(owner, cluster.getRegion()), cluster.getId(), cluster);
    }

    private boolean realMode() {
        return clusterManager != null && config != null && !config.services().oke().mock();
    }

    static String newApiToken() {
        byte[] bytes = new byte[API_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public void clear() {
        pendingClusters.clear();
        reconcileQueue.clear();
        finishedNodes.clear();
        capWarnings.clear();
        poolsPendingSince.clear();
        clusters.clear();
        nodePools.clear();
        if (clusterManager != null) {
            clusterManager.clear();
        }
    }

    public record CreateClusterResult(String workRequestId, StoredOkeCluster cluster) {}

    /**
     * The optional settings of CreateClusterDetails, or of UpdateClusterDetails (where
     * {@code endpointConfig} and {@code clusterPodNetworkOptions} cannot change).
     */
    public record ClusterSettings(String type, Map<String, Object> endpointConfig, Map<String, Object> options,
                                  Map<String, Object> imagePolicyConfig,
                                  List<Map<String, Object>> clusterPodNetworkOptions) {
        static final ClusterSettings NONE = new ClusterSettings(null, null, null, null, null);
    }
    public record CreateNodePoolResult(String workRequestId, StoredNodePool nodePool) {}

    /** The optional settings of CreateNodePoolDetails and UpdateNodePoolDetails, as the client sent them. */
    public record NodePoolSettings(List<String> subnetIds, Map<String, Object> nodeConfigDetails,
                                   Map<String, Object> nodeShapeConfig, List<Map<String, Object>> initialNodeLabels,
                                   Map<String, Object> nodeSourceDetails, String sshPublicKey,
                                   Map<String, String> nodeMetadata, Map<String, Object> nodePoolCyclingDetails,
                                   Map<String, Object> nodeEvictionNodePoolSettings) {
        static final NodePoolSettings NONE = new NodePoolSettings(null, null, null, null, null, null, null, null, null);
    }

    // ── Clusters ─────────────────────────────────────────────────────────────

    public CreateClusterResult createCluster(String compartmentId, String name, String vcnId,
                                String kubernetesVersion, String kmsKeyId,
                                Map<String, String> freeformTags,
                                Map<String, Map<String, Object>> definedTags) {
        return createCluster(compartmentId, name, vcnId, kubernetesVersion, kmsKeyId, freeformTags, definedTags,
                ClusterSettings.NONE);
    }

    public CreateClusterResult createCluster(String compartmentId, String name, String vcnId,
                                String kubernetesVersion, String kmsKeyId,
                                Map<String, String> freeformTags,
                                Map<String, Map<String, Object>> definedTags,
                                ClusterSettings settings) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        if (name == null || name.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: name");
        }
        if (vcnId == null || vcnId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: vcnId");
        }
        String version = OkeVersions.requireForNewCluster(kubernetesVersion);
        String type = OkeClusterSettings.type(settings.type());
        Map<String, Object> endpointConfig = OkeClusterSettings.endpointConfig(settings.endpointConfig());
        Map<String, Object> options = OkeClusterSettings.createOptions(settings.options());
        Map<String, Object> imagePolicyConfig = OkeClusterSettings.imagePolicyConfig(settings.imagePolicyConfig());
        List<Map<String, Object>> podNetworkOptions =
                OkeClusterSettings.podNetworkOptions(settings.clusterPodNetworkOptions());

        String clusterId = Ocids.generate("cluster", ociContext.realm(), ociContext.regionCode());
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId(clusterId);
        cluster.setName(name);
        cluster.setCompartmentId(compartmentId);
        cluster.setVcnId(vcnId);
        cluster.setKubernetesVersion(version);
        cluster.setKmsKeyId(kmsKeyId);
        cluster.setLifecycleState("CREATING");
        cluster.setLifecycleDetails(null);
        cluster.setMetadata(new StoredOkeCluster.ClusterMetadata(Instant.now()));
        cluster.setFreeformTags(freeformTags);
        cluster.setDefinedTags(definedTags);
        cluster.setType(type);
        cluster.setEndpointConfig(endpointConfig);
        cluster.setOptions(options);
        cluster.setImagePolicyConfig(imagePolicyConfig);
        cluster.setClusterPodNetworkOptions(podNetworkOptions);
        cluster.setEtag(Etags.newEtag());
        cluster.setApiToken(newApiToken());
        cluster.setTenancyId(tenancyId.get());
        cluster.setRegion(ociContext.region());

        if (realMode()) {
            clusterManager.startCluster(cluster);
        } else {
            cluster.setEndpoints(MOCK_ENDPOINTS);
            cluster.setLifecycleState("ACTIVE");
        }

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "cluster", "CREATED", clusterId, "/20180222/clusters/" + clusterId
        );
        boolean creating = "CREATING".equals(cluster.getLifecycleState());
        String workRequestId = creating
                ? workRequests.inProgress("oke", "CLUSTER_CREATE", compartmentId, List.of(res))
                : workRequests.succeeded("oke", "CLUSTER_CREATE", compartmentId, List.of(res));
        if (creating) {
            cluster.setCreateWorkRequestId(workRequestId);
        }
        clusters.put(clusterId, cluster);
        if (creating) {
            // Only once stored: the poller drops a pending entry whose cluster it cannot find.
            pendingClusters.put(clusterId, new PendingCluster(cluster.getTenancyId(), clock.instant(), workRequestId));
        }
        return new CreateClusterResult(workRequestId, cluster);
    }

    /**
     * OCI does not say what a node pool on a cluster that is not ACTIVE gets, so this is
     * floci-oci's choice: a CREATING cluster is 409 IncorrectState, which SDKs retry until the
     * cluster is ready, and a cluster that failed or is going away is 409 Conflict.
     */
    private static void requireClusterTakesNodePools(StoredOkeCluster cluster) {
        String state = cluster.getLifecycleState();
        if ("CREATING".equals(state)) {
            throw new OciException("IncorrectState", "Cluster " + cluster.getId()
                    + " is CREATING; node pools can be created once it is ACTIVE", 409);
        }
        if ("FAILED".equals(state) || "DELETING".equals(state) || "DELETED".equals(state)) {
            throw OciException.conflict("Cluster " + cluster.getId() + " is " + state
                    + " and cannot take node pools");
        }
    }

    public StoredOkeCluster getCluster(String clusterId) {
        return clusters.get(clusterId)
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound("Cluster not found: " + clusterId));
    }

    public List<StoredOkeCluster> listClusters(String compartmentId) {
        return clusters.scan(k -> true).stream()
                .filter(c -> compartmentId == null || compartmentId.equals(c.getCompartmentId()))
                .toList();
    }

    public String updateCluster(String clusterId, String name, String kubernetesVersion) {
        return updateCluster(clusterId, name, kubernetesVersion, null, null, ClusterSettings.NONE, null);
    }

    /**
     * UpdateCluster: the name, version, tags, type, image policy and the options
     * UpdateClusterOptionsDetails allows. Everything is checked before anything changes. The
     * cluster updates are synchronized, so two writes carrying the same etag cannot both pass
     * {@code if-match}.
     */
    public synchronized String updateCluster(String clusterId, String name, String kubernetesVersion,
                                Map<String, String> freeformTags, Map<String, Map<String, Object>> definedTags,
                                ClusterSettings settings, String ifMatch) {
        StoredOkeCluster cluster = getCluster(clusterId);
        Etags.checkIfMatch(ifMatch, cluster.getEtag());
        String type = OkeClusterSettings.type(settings.type());
        Map<String, Object> imagePolicyConfig = OkeClusterSettings.imagePolicyConfig(settings.imagePolicyConfig());
        Map<String, Object> options = OkeClusterSettings.updatedOptions(cluster.getOptions(), settings.options());
        boolean upgrade = kubernetesVersion != null && !kubernetesVersion.isBlank()
                && !kubernetesVersion.equals(cluster.getKubernetesVersion());
        if (upgrade) {
            requireActive(cluster, "upgraded");
            OkeVersions.requireClusterUpgrade(cluster.getKubernetesVersion(), kubernetesVersion,
                    nodeVersions(clusterId));
        }
        if (name != null && !name.isBlank()) {
            cluster.setName(name);
        }
        if (upgrade) {
            cluster.setKubernetesVersion(kubernetesVersion);
        }
        if (freeformTags != null) {
            cluster.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            cluster.setDefinedTags(definedTags);
        }
        if (type != null) {
            cluster.setType(type);
        }
        if (imagePolicyConfig != null) {
            cluster.setImagePolicyConfig(imagePolicyConfig);
        }
        cluster.setOptions(options);
        if (upgrade && realMode()) {
            return upgradeControlPlane(cluster);
        }
        return clusterUpdated(cluster);
    }

    /**
     * Recreates the k3s server on the new version's image, on the same volume, so the cluster keeps
     * its data, CA and agents. The cluster is UPDATING with an IN_PROGRESS CLUSTER_UPDATE work
     * request until the new server answers, as on create.
     */
    private String upgradeControlPlane(StoredOkeCluster cluster) {
        cluster.setEtag(Etags.newEtag());
        cluster.setLifecycleState("UPDATING");
        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "cluster", "UPDATED", cluster.getId(), "/20180222/clusters/" + cluster.getId()
        );
        String workRequestId = workRequests.inProgress("oke", "CLUSTER_UPDATE", cluster.getCompartmentId(),
                List.of(res));
        clusters.put(cluster.getId(), cluster);
        PendingCluster pending = new PendingCluster(cluster.getTenancyId(), clock.instant(), workRequestId);
        pendingClusters.put(cluster.getId(), pending);
        try {
            clusterManager.restartCluster(cluster);
        } catch (RuntimeException e) {
            finishCreate(pending, cluster, "FAILED", "FAILED", "The k3s server did not start on "
                    + cluster.getKubernetesVersion() + ": " + e.getMessage());
        }
        return workRequestId;
    }

    /** OCI does not say what an operation on a cluster that is not ACTIVE gets; same rule as node pools. */
    private static void requireActive(StoredOkeCluster cluster, String action) {
        String state = cluster.getLifecycleState();
        if ("CREATING".equals(state) || "UPDATING".equals(state)) {
            throw new OciException("IncorrectState", "Cluster " + cluster.getId() + " is " + state
                    + " and cannot be " + action + " until it is ACTIVE", 409);
        }
        if (!"ACTIVE".equals(state)) {
            throw OciException.conflict("Cluster " + cluster.getId() + " is " + state + " and cannot be " + action);
        }
    }

    /** The versions configured on the cluster's node pools and running on their nodes. */
    private List<String> nodeVersions(String clusterId) {
        List<String> versions = new ArrayList<>();
        for (StoredNodePool pool : listNodePools(null, clusterId)) {
            versions.add(pool.getKubernetesVersion());
            pool.getNodes().forEach(node -> versions.add(node.getKubernetesVersion()));
        }
        return versions;
    }

    /** UpdateClusterEndpointConfig: NSGs, public IP and security attributes; the subnet stays. */
    public synchronized String updateClusterEndpointConfig(String clusterId, Map<String, Object> details,
                                                           String ifMatch) {
        StoredOkeCluster cluster = getCluster(clusterId);
        Etags.checkIfMatch(ifMatch, cluster.getEtag());
        cluster.setEndpointConfig(OkeClusterSettings.updatedEndpointConfig(cluster.getEndpointConfig(),
                details != null ? details : Map.of()));
        return clusterUpdated(cluster);
    }

    /**
     * ClusterMigrateToNativeVcn: gives a cluster without one its endpoint config, which is what
     * Terraform calls when {@code endpoint_config} is added to an existing cluster. There is no
     * VCN, so nothing else changes.
     */
    public synchronized String migrateClusterToNativeVcn(String clusterId, Map<String, Object> details,
                                                         String ifMatch) {
        StoredOkeCluster cluster = getCluster(clusterId);
        Etags.checkIfMatch(ifMatch, cluster.getEtag());
        Object endpointConfig = details != null ? details.get("endpointConfig") : null;
        if (!(endpointConfig instanceof Map<?, ?> config)) {
            throw OciException.missingParameter("Missing required parameter: endpointConfig");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> requested = (Map<String, Object>) config;
        cluster.setEndpointConfig(OkeClusterSettings.endpointConfig(requested));
        return clusterUpdated(cluster);
    }

    private String clusterUpdated(StoredOkeCluster cluster) {
        cluster.setEtag(Etags.newEtag());
        clusters.put(cluster.getId(), cluster);
        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "cluster", "UPDATED", cluster.getId(), "/20180222/clusters/" + cluster.getId()
        );
        return workRequests.succeeded("oke", "CLUSTER_UPDATE", cluster.getCompartmentId(), List.of(res));
    }

    public String deleteCluster(String clusterId) {
        StoredOkeCluster cluster = getCluster(clusterId);
        if (pendingClusters.remove(clusterId) != null && cluster.getCreateWorkRequestId() != null) {
            workRequests.finish(cluster.getTenancyId(), cluster.getRegion(),
                    cluster.getCreateWorkRequestId(), "CANCELED");
        }
        reconcileQueue.remove(clusterId);
        capWarnings.remove(clusterId);
        if (realMode()) {
            clusterManager.stopCluster(cluster);
        }

        List<StoredNodePool> dependentPools = listNodePools(null, clusterId);
        for (StoredNodePool pool : dependentPools) {
            pool.setLifecycleState("DELETED");
            pool.getNodes().forEach(node -> finishedNodes.remove(node.getId()));
            poolsPendingSince.remove(pool.getId());
            nodePools.delete(pool.getId());
        }

        cluster.setLifecycleState("DELETED");
        clusters.delete(clusterId);

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "cluster", "DELETED", clusterId, "/20180222/clusters/" + clusterId
        );
        return workRequests.succeeded("oke", "CLUSTER_DELETE", cluster.getCompartmentId(), List.of(res));
    }

    public CreateNodePoolResult createNodePool(String compartmentId, String clusterId, String name,
                                 String kubernetesVersion, String nodeShape,
                                 int quantityPerSubnet,
                                 Map<String, String> freeformTags,
                                 Map<String, Map<String, Object>> definedTags) {
        return createNodePool(compartmentId, clusterId, name, kubernetesVersion, nodeShape,
                quantityPerSubnet > 0 ? quantityPerSubnet : null, freeformTags, definedTags, NodePoolSettings.NONE);
    }

    public synchronized CreateNodePoolResult createNodePool(String compartmentId, String clusterId, String name,
                                 String kubernetesVersion, String nodeShape,
                                 Integer quantityPerSubnet,
                                 Map<String, String> freeformTags,
                                 Map<String, Map<String, Object>> definedTags,
                                 NodePoolSettings settings) {
        if (compartmentId == null || compartmentId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: compartmentId");
        }
        if (clusterId == null || clusterId.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: clusterId");
        }
        StoredOkeCluster cluster = getCluster(clusterId);
        requireClusterTakesNodePools(cluster);
        if (name == null || name.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: name");
        }
        if (quantityPerSubnet != null && quantityPerSubnet < 0) {
            throw OciException.invalidParameter("quantityPerSubnet must not be negative");
        }

        String nodePoolId = Ocids.generate("nodepool", ociContext.realm(), ociContext.regionCode());
        StoredNodePool pool = new StoredNodePool();
        pool.setId(nodePoolId);
        pool.setName(name);
        pool.setCompartmentId(compartmentId);
        pool.setClusterId(clusterId);
        String poolVersion = kubernetesVersion != null && !kubernetesVersion.isBlank()
                ? kubernetesVersion : cluster.getKubernetesVersion();
        OkeVersions.requireNodePoolVersion(cluster.getKubernetesVersion(), poolVersion);
        pool.setKubernetesVersion(poolVersion);
        pool.setNodeShape(nodeShape != null ? nodeShape : "VM.Standard.E4.Flex");
        pool.setQuantityPerSubnet(quantityPerSubnet != null ? quantityPerSubnet : 1);
        pool.setSubnetIds(settings.subnetIds());
        pool.setNodeConfigDetails(OkeNodePools.nodeConfigDetails(settings.nodeConfigDetails(), true));
        pool.setNodeShapeConfig(OkeNodePools.shapeConfig(settings.nodeShapeConfig()));
        pool.setInitialNodeLabels(OkeNodePools.initialNodeLabels(settings.initialNodeLabels()));
        OkeNodeLabels.validate(pool.getInitialNodeLabels());
        OkeShapes.size(pool.getNodeShape(), pool.getNodeShapeConfig());
        pool.setNodeSourceDetails(settings.nodeSourceDetails());
        pool.setSshPublicKey(settings.sshPublicKey());
        pool.setNodeMetadata(settings.nodeMetadata());
        pool.setNodePoolCyclingDetails(OkeNodePools.cyclingDetails(settings.nodePoolCyclingDetails()));
        pool.setNodeEvictionNodePoolSettings(OkeNodePools.evictionSettings(settings.nodeEvictionNodePoolSettings()));
        pool.setLifecycleState("ACTIVE");
        pool.setTimeCreated(Instant.now());
        pool.setFreeformTags(freeformTags);
        pool.setDefinedTags(definedTags);
        pool.setTenancyId(tenancyId.get());
        pool.setRegion(ociContext.region());
        resizeNodes(cluster, pool);

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "nodepool", "CREATED", nodePoolId, "/20180222/nodePools/" + nodePoolId
        );
        String workRequestId;
        if (realMode() && !pool.getNodes().isEmpty()) {
            // ACTIVE once the poller sees every node Ready, as OKE does.
            pool.setLifecycleState("CREATING");
            workRequestId = workRequests.inProgress("oke", "NODEPOOL_CREATE", compartmentId, List.of(res));
            pool.setPendingWorkRequestId(workRequestId);
            poolsPendingSince.put(nodePoolId, clock.instant());
        } else {
            workRequestId = workRequests.succeeded("oke", "NODEPOOL_CREATE", compartmentId, List.of(res));
        }
        nodePools.put(nodePoolId, pool);
        requestReconcile(cluster);
        return new CreateNodePoolResult(workRequestId, pool);
    }

    /**
     * Brings the pool's node list to its requested size. Nodes take the next free address of the
     * cluster's node subnet; without a k3s agent behind them they are ACTIVE at once.
     */
    private List<StoredNode> resizeNodes(StoredOkeCluster cluster, StoredNodePool pool) {
        Set<Integer> usedHosts = new HashSet<>();
        for (StoredNodePool other : listNodePools(null, cluster.getId())) {
            if (!other.getId().equals(pool.getId())) {
                other.getNodes().forEach(node -> usedHosts.add(OkeNodePools.hostOf(node.getPrivateIp())));
            }
        }
        pool.getNodes().forEach(node -> usedHosts.add(OkeNodePools.hostOf(node.getPrivateIp())));
        String nodeSubnet = cluster.getNodeSubnet() != null ? cluster.getNodeSubnet() : OkeNodePools.DEFAULT_NODE_SUBNET;
        List<StoredNode> removed = OkeNodePools.resize(pool, cluster.getId(), nodeSubnet,
                usedHosts, () -> Ocids.generate("instance", ociContext.realm(), ociContext.regionCode()),
                this::defaultAvailabilityDomain);
        if (!realMode()) {
            for (StoredNode node : pool.getNodes()) {
                if ("CREATING".equals(node.getLifecycleState())) {
                    node.setLifecycleState("ACTIVE");
                }
            }
        }
        for (StoredNode node : removed) {
            finishedNodes.remove(node.getId());
            if (realMode()) {
                clusterManager.removeNode(cluster, node, true);
            }
        }
        return removed;
    }

    /** The availability domain of nodes placed through the deprecated {@code subnetIds}. */
    private String defaultAvailabilityDomain() {
        if (identity == null) {
            return null;
        }
        List<Map<String, Object>> availabilityDomains = identity.availabilityDomains(null);
        return availabilityDomains.isEmpty() ? null : (String) availabilityDomains.getFirst().get("name");
    }

    public StoredNodePool getNodePool(String nodePoolId) {
        return nodePools.get(nodePoolId)
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound("NodePool not found: " + nodePoolId));
    }

    public List<StoredNodePool> listNodePools(String compartmentId, String clusterId) {
        return nodePools.scan(k -> true).stream()
                .filter(p -> compartmentId == null || compartmentId.equals(p.getCompartmentId()))
                .filter(p -> clusterId == null || clusterId.equals(p.getClusterId()))
                .toList();
    }

    public String updateNodePool(String nodePoolId, String name, String kubernetesVersion, Integer quantityPerSubnet) {
        return updateNodePool(nodePoolId, name, kubernetesVersion, quantityPerSubnet, null, null, null,
                NodePoolSettings.NONE);
    }

    /**
     * UpdateNodePool: every field is optional and replaces the stored one. A new size, placement or
     * subnet list adds or removes nodes.
     */
    public synchronized String updateNodePool(String nodePoolId, String name, String kubernetesVersion,
                                              Integer quantityPerSubnet, String nodeShape,
                                              Map<String, String> freeformTags,
                                              Map<String, Map<String, Object>> definedTags,
                                              NodePoolSettings settings) {
        StoredNodePool pool = getNodePool(nodePoolId);
        Map<String, Object> nodeConfigDetails = OkeNodePools.nodeConfigDetails(settings.nodeConfigDetails(), false);
        Map<String, Object> shapeConfig = OkeNodePools.shapeConfig(settings.nodeShapeConfig());
        List<Map<String, Object>> labels = OkeNodePools.initialNodeLabels(settings.initialNodeLabels());
        OkeNodeLabels.validate(labels);
        OkeShapes.size(nodeShape != null && !nodeShape.isBlank() ? nodeShape : pool.getNodeShape(),
                shapeConfig != null ? shapeConfig : pool.getNodeShapeConfig());
        if (quantityPerSubnet != null && quantityPerSubnet < 0) {
            throw OciException.invalidParameter("quantityPerSubnet must not be negative");
        }
        StoredOkeCluster cluster = getCluster(pool.getClusterId());
        if (kubernetesVersion != null && !kubernetesVersion.isBlank()
                && !kubernetesVersion.equals(pool.getKubernetesVersion())) {
            OkeVersions.requireNodePoolVersion(cluster.getKubernetesVersion(), kubernetesVersion);
        }
        if (name != null && !name.isBlank()) {
            pool.setName(name);
        }
        if (kubernetesVersion != null && !kubernetesVersion.isBlank()) {
            pool.setKubernetesVersion(kubernetesVersion);
        }
        if (quantityPerSubnet != null) {
            pool.setQuantityPerSubnet(quantityPerSubnet);
        }
        if (nodeShape != null && !nodeShape.isBlank()) {
            pool.setNodeShape(nodeShape);
        }
        if (settings.subnetIds() != null) {
            pool.setSubnetIds(settings.subnetIds());
        }
        if (nodeConfigDetails != null) {
            Map<String, Object> merged = pool.getNodeConfigDetails() != null
                    ? new LinkedHashMap<>(pool.getNodeConfigDetails()) : new LinkedHashMap<>();
            merged.putAll(nodeConfigDetails);
            pool.setNodeConfigDetails(merged);
        }
        if (shapeConfig != null) {
            pool.setNodeShapeConfig(shapeConfig);
        }
        if (labels != null) {
            pool.setInitialNodeLabels(labels);
        }
        if (settings.nodeSourceDetails() != null) {
            pool.setNodeSourceDetails(settings.nodeSourceDetails());
        }
        if (settings.sshPublicKey() != null) {
            pool.setSshPublicKey(settings.sshPublicKey());
        }
        if (settings.nodeMetadata() != null) {
            pool.setNodeMetadata(settings.nodeMetadata());
        }
        if (settings.nodePoolCyclingDetails() != null) {
            pool.setNodePoolCyclingDetails(OkeNodePools.cyclingDetails(settings.nodePoolCyclingDetails()));
        }
        if (settings.nodeEvictionNodePoolSettings() != null) {
            pool.setNodeEvictionNodePoolSettings(OkeNodePools.evictionSettings(settings.nodeEvictionNodePoolSettings()));
        }
        if (freeformTags != null) {
            pool.setFreeformTags(freeformTags);
        }
        if (definedTags != null) {
            pool.setDefinedTags(definedTags);
        }
        resizeNodes(cluster, pool);

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "nodepool", "UPDATED", nodePoolId, "/20180222/nodePools/" + nodePoolId
        );
        String workRequestId;
        boolean waitsForNodes = pool.getNodes().stream().anyMatch(node -> !"ACTIVE".equals(node.getLifecycleState()));
        if (realMode() && waitsForNodes) {
            pool.setLifecycleState("UPDATING");
            workRequestId = workRequests.inProgress("oke", "NODEPOOL_UPDATE", pool.getCompartmentId(), List.of(res));
            pool.setPendingWorkRequestId(workRequestId);
            poolsPendingSince.put(nodePoolId, clock.instant());
        } else {
            workRequestId = workRequests.succeeded("oke", "NODEPOOL_UPDATE", pool.getCompartmentId(), List.of(res));
        }
        nodePools.put(nodePoolId, pool);
        requestReconcile(cluster);
        return workRequestId;
    }

    public synchronized String deleteNodePool(String nodePoolId) {
        StoredNodePool pool = getNodePool(nodePoolId);
        Optional<StoredOkeCluster> cluster = clusters.get(pool.getClusterId());
        for (StoredNode node : pool.getNodes()) {
            finishedNodes.remove(node.getId());
            if (realMode() && cluster.isPresent()) {
                clusterManager.removeNode(cluster.get(), node, true);
            }
        }
        if (pool.getPendingWorkRequestId() != null) {
            workRequests.finish(pool.getTenancyId(), pool.getRegion(), pool.getPendingWorkRequestId(), "CANCELED");
        }
        poolsPendingSince.remove(nodePoolId);
        pool.setLifecycleState("DELETED");
        nodePools.delete(nodePoolId);
        cluster.ifPresent(this::requestReconcile);

        StoredWorkRequest.Resource res = WorkRequestService.resource(
            "nodepool", "DELETED", nodePoolId, "/20180222/nodePools/" + nodePoolId
        );
        return workRequests.succeeded("oke", "NODEPOOL_DELETE", pool.getCompartmentId(), List.of(res));
    }

    // ── Worker node reconcile (real mode) ───────────────────────────────────

    /** Asks the poller to bring the cluster's agents in line with its node pools. */
    private void requestReconcile(StoredOkeCluster cluster) {
        if (realMode() && cluster.getTenancyId() != null) {
            reconcileQueue.put(cluster.getId(), cluster.getTenancyId());
        }
    }

    private void reconcileQueued() {
        for (Map.Entry<String, String> entry : reconcileQueue.entrySet()) {
            try {
                if (reconcile(entry.getValue(), entry.getKey())) {
                    reconcileQueue.remove(entry.getKey(), entry.getValue());
                }
            } catch (RuntimeException e) {
                LOG.warnv(e, "OKE node reconcile failed for cluster {0}", entry.getKey());
            }
        }
    }

    /** One node to run, with what starting its agent needs, read under the service lock. */
    private record NodeWork(StoredNodePool pool, StoredNode node) {}

    /**
     * One pass over a cluster's worker nodes: the first {@code max-nodes-per-cluster} nodes, by node
     * pool creation order, get a k3s agent; nodes that are Ready get the labels and annotation OKE adds
     * after the join; a pool waiting on its nodes becomes ACTIVE once all of them are, and its work
     * request finishes. Docker and kubectl calls run outside the service lock.
     *
     * @return whether the cluster is settled and can leave the queue
     */
    boolean reconcile(String owner, String clusterId) {
        StoredOkeCluster cluster;
        List<NodeWork> running = new ArrayList<>();
        synchronized (this) {
            Optional<StoredOkeCluster> found = findCluster(owner, clusterId);
            if (found.isEmpty() || "FAILED".equals(found.get().getLifecycleState())) {
                return true;
            }
            cluster = found.get();
            if (!"ACTIVE".equals(cluster.getLifecycleState())) {
                return false;
            }
            int budget = config.services().oke().maxNodesPerCluster();
            List<String> shortPools = new ArrayList<>();
            for (StoredNodePool pool : clusterPools(owner, clusterId)) {
                int skipped = 0;
                for (StoredNode node : List.copyOf(pool.getNodes())) {
                    if (budget > 0) {
                        budget--;
                        running.add(new NodeWork(pool, node));
                    } else {
                        skipped++;
                    }
                }
                if (skipped > 0) {
                    shortPools.add(pool.getName() + " (" + skipped + " of " + pool.getNodes().size() + " nodes)");
                }
            }
            String capWarning = shortPools.isEmpty() ? null : String.join(", ", shortPools);
            if (capWarning != null && !capWarning.equals(capWarnings.put(clusterId, capWarning))) {
                LOG.warnv("OKE cluster {0} asks for more nodes than max-nodes-per-cluster ({1}) allows; these node"
                        + " pools run without a k3s agent for some nodes: {2}", clusterId,
                        config.services().oke().maxNodesPerCluster(), capWarning);
            } else if (capWarning == null) {
                capWarnings.remove(clusterId);
            }
        }

        if (cluster.getJoinToken() == null) {
            Optional<String> token = clusterManager.readJoinToken(cluster);
            if (token.isEmpty()) {
                return false;
            }
            cluster.setJoinToken(token.get());
            synchronized (this) {
                findCluster(owner, clusterId).ifPresent(stored -> {
                    stored.setJoinToken(token.get());
                    putCluster(owner, stored);
                });
            }
        }

        Set<String> runningIds = new HashSet<>();
        Map<String, String> startFailures = new HashMap<>();
        for (NodeWork work : running) {
            runningIds.add(work.node().getId());
            try {
                if (clusterManager.ensureNode(cluster, work.pool(), work.node())) {
                    finishedNodes.remove(work.node().getId());
                }
            } catch (RuntimeException e) {
                LOG.warnv("Could not start the k3s agent of node {0}: {1}", work.node().getId(), e.getMessage());
                startFailures.put(work.node().getId(), e.getMessage());
            }
        }
        Map<String, Boolean> readiness = clusterManager.nodeReadiness(cluster).orElse(null);
        if (readiness != null) {
            for (NodeWork work : running) {
                StoredNode node = work.node();
                if (Boolean.TRUE.equals(readiness.get(node.getPrivateIp())) && !finishedNodes.contains(node.getId())
                        && clusterManager.finishNode(cluster, work.pool(), node)) {
                    finishedNodes.add(node.getId());
                }
            }
            Set<String> expected = new HashSet<>();
            running.forEach(work -> expected.add(work.node().getPrivateIp()));
            clusterManager.deleteStaleNodes(cluster, expected, readiness);
        }

        List<StoredNode> orphans = new ArrayList<>();
        boolean settled;
        synchronized (this) {
            if (findCluster(owner, clusterId).isEmpty()) {
                return true;
            }
            settled = true;
            Set<String> present = new HashSet<>();
            for (StoredNodePool pool : clusterPools(owner, clusterId)) {
                boolean allActive = true;
                for (StoredNode node : pool.getNodes()) {
                    present.add(node.getId());
                    if (!runningIds.contains(node.getId())) {
                        node.setRunning(false);
                        node.setContainerName(null);
                        node.setLifecycleState("ACTIVE");
                        node.setLifecycleDetails("floci-oci runs at most "
                                + config.services().oke().maxNodesPerCluster()
                                + " nodes per cluster (max-nodes-per-cluster): this node has no Kubernetes node");
                        continue;
                    }
                    node.setRunning(true);
                    node.setContainerName(clusterManager.nodeContainerName(node));
                    node.setVolumeName(clusterManager.nodeVolumeName(node));
                    if (finishedNodes.contains(node.getId())) {
                        node.setLifecycleState("ACTIVE");
                        node.setLifecycleDetails(null);
                    } else {
                        allActive = false;
                        settled = false;
                        node.setLifecycleDetails(startFailures.get(node.getId()));
                    }
                }
                finishPoolIfDone(pool, allActive);
                if (isPending(pool)) {
                    settled = false;
                }
                TenancyAwareStorageBackend.putIn(nodePools,
                        TenancyAwareStorageBackend.regionalPartition(owner, pool.getRegion()), pool.getId(), pool);
            }
            for (NodeWork work : running) {
                if (!present.contains(work.node().getId())) {
                    orphans.add(work.node());
                }
            }
        }
        for (StoredNode orphan : orphans) {
            clusterManager.removeNode(cluster, orphan, true);
        }
        return settled;
    }

    private static boolean isPending(StoredNodePool pool) {
        return "CREATING".equals(pool.getLifecycleState()) || "UPDATING".equals(pool.getLifecycleState());
    }

    /** A pending pool becomes ACTIVE once its nodes are, or FAILED past the ready timeout. */
    private void finishPoolIfDone(StoredNodePool pool, boolean allActive) {
        if (!isPending(pool)) {
            return;
        }
        Instant since = poolsPendingSince.computeIfAbsent(pool.getId(), id -> clock.instant());
        if (allActive) {
            finishPool(pool, "ACTIVE", "SUCCEEDED", null);
        } else if (clock.instant().isAfter(since.plusSeconds(config.services().oke().readyTimeoutSeconds()))) {
            finishPool(pool, "FAILED", "FAILED", "The nodes of node pool " + pool.getId()
                    + " were not Ready after " + config.services().oke().readyTimeoutSeconds() + " seconds");
        }
    }

    private void finishPool(StoredNodePool pool, String lifecycleState, String workRequestStatus, String details) {
        pool.setLifecycleState(lifecycleState);
        pool.setLifecycleDetails(details);
        poolsPendingSince.remove(pool.getId());
        if (pool.getPendingWorkRequestId() != null) {
            List<StoredWorkRequest.ErrorEntry> errors = details != null
                    ? List.of(new StoredWorkRequest.ErrorEntry("InternalServerError", details))
                    : List.of();
            workRequests.finish(pool.getTenancyId(), pool.getRegion(), pool.getPendingWorkRequestId(),
                    workRequestStatus, errors);
            pool.setPendingWorkRequestId(null);
        }
        LOG.infov("OKE node pool {0} is {1}", pool.getId(), lifecycleState);
    }

    /** The cluster's node pools in creation order, the order they get nodes in under the cap. */
    private List<StoredNodePool> clusterPools(String owner, String clusterId) {
        return TenancyAwareStorageBackend.scanTenancy(nodePools, owner).stream()
                .filter(pool -> clusterId.equals(pool.getClusterId()))
                .sorted(Comparator.comparing((StoredNodePool pool) -> pool.getTimeCreated() != null
                        ? pool.getTimeCreated() : Instant.EPOCH).thenComparing(StoredNodePool::getId))
                .toList();
    }

    @Override
    public List<String> resourcesIn(String tenancyId, String compartmentId) {
        return Stream.concat(
                CompartmentUsage.live(clusters, tenancyId, compartmentId, StoredOkeCluster::getCompartmentId,
                        StoredOkeCluster::getLifecycleState, c -> "cluster " + c.getId()),
                CompartmentUsage.live(nodePools, tenancyId, compartmentId, StoredNodePool::getCompartmentId,
                        StoredNodePool::getLifecycleState, p -> "node pool " + p.getId())).toList();
    }
}
