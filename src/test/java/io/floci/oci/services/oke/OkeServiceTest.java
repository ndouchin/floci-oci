package io.floci.oci.services.oke;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.OciContext;
import io.floci.oci.core.common.OciException;
import io.floci.oci.core.storage.InMemoryStorage;
import io.floci.oci.core.storage.StorageBackend;
import io.floci.oci.core.storage.TenancyAwareStorageBackend;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.oci.services.oke.model.StoredLoadBalancer;
import io.floci.oci.services.oke.model.StoredNode;
import io.floci.oci.services.oke.model.StoredNodePool;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OkeServiceTest {

    private static final String COMPARTMENT = "ocid1.compartment.oc1..oketestcompartment";
    private static final String VCN = "ocid1.vcn.oc1.iad.testvcn";

    private OkeService service;
    private InMemoryStorage<String, StoredOkeCluster> clusters;
    private InMemoryStorage<String, StoredNodePool> nodePools;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        WorkRequestService workRequests = mock(WorkRequestService.class);

        clusters = new InMemoryStorage<>();
        nodePools = new InMemoryStorage<>();

        service = new OkeService(clusters, nodePools, config, null, workRequests, null);
    }

    @Test
    void createAndGetCluster() {
        OkeService.CreateClusterResult res = service.createCluster(COMPARTMENT, "my-cluster", VCN, OkeVersions.DEFAULT_VERSION, null, Map.of("env", "test"), null);
        assertNotNull(res.cluster());
        assertNotNull(clusters);
        List<StoredOkeCluster> list = service.listClusters(COMPARTMENT);
        assertEquals(1, list.size());
        StoredOkeCluster cluster = list.get(0);
        assertEquals("my-cluster", cluster.getName());
        assertEquals(COMPARTMENT, cluster.getCompartmentId());

        StoredOkeCluster fetched = service.getCluster(cluster.getId());
        assertEquals(cluster.getId(), fetched.getId());
    }

    @Test
    void aRealModeClusterRecordsTheConfiguredLoadBalancerMode() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.defaultRealm()).thenReturn("oc1");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.services().oke().mock()).thenReturn(false);
        lenient().when(config.services().oke().loadBalancer().mode()).thenReturn("off");
        OkeClusterManager manager = mock(OkeClusterManager.class);
        OkeService real = new OkeService(new InMemoryStorage<>(), new InMemoryStorage<>(), config, null,
                mock(WorkRequestService.class), manager);

        StoredOkeCluster cluster = real.createCluster(COMPARTMENT, "lb-mode", VCN, OkeVersions.DEFAULT_VERSION,
                null, null, null).cluster();

        assertEquals("off", cluster.getLoadBalancerMode());
        verify(manager).startCluster(argThat(started -> "off".equals(started.getLoadBalancerMode())));
    }

    @Test
    void ocidUsesMappedRegionShortCode() {
        OkeService.CreateClusterResult res = service.createCluster(COMPARTMENT, "region-cluster", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);
        assertTrue(res.cluster().getId().startsWith("ocid1.cluster.oc1.iad."));
    }

    @Test
    void createClusterWithDuplicateNameReturnsNewCluster() {
        OkeService.CreateClusterResult res1 = service.createCluster(COMPARTMENT, "same-name", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);
        OkeService.CreateClusterResult res2 = service.createCluster(COMPARTMENT, "same-name", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);

        assertNotNull(res1.cluster().getId());
        assertNotNull(res2.cluster().getId());
        assertTrue(!res1.cluster().getId().equals(res2.cluster().getId()));
    }

    @Test
    void createNodePoolWithDuplicateNameReturnsNewNodePool() {
        OkeService.CreateClusterResult cRes = service.createCluster(COMPARTMENT, "cluster-dup", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);
        String clusterId = cRes.cluster().getId();

        OkeService.CreateNodePoolResult np1 = service.createNodePool(COMPARTMENT, clusterId, "pool-dup", OkeVersions.DEFAULT_VERSION, "VM.Standard.E4.Flex", 2, null, null);
        OkeService.CreateNodePoolResult np2 = service.createNodePool(COMPARTMENT, clusterId, "pool-dup", OkeVersions.DEFAULT_VERSION, "VM.Standard.E4.Flex", 2, null, null);

        assertNotNull(np1.nodePool().getId());
        assertNotNull(np2.nodePool().getId());
        assertTrue(!np1.nodePool().getId().equals(np2.nodePool().getId()));
    }

    @Test
    void getClusterNotFoundThrowsOciException() {
        assertThrows(OciException.class, () -> service.getCluster("ocid1.cluster.oc1.iad.nonexistent"));
    }

    @Test
    void createNodePoolNonexistentClusterThrowsOciException() {
        assertThrows(OciException.class, () ->
            service.createNodePool(COMPARTMENT, "ocid1.cluster.oc1.iad.nonexistent", "pool-1", OkeVersions.DEFAULT_VERSION, "VM.Standard.E4.Flex", 2, null, null)
        );
    }

    private static OkeService.ClusterSettings fullSettings() {
        return new OkeService.ClusterSettings("ENHANCED_CLUSTER",
                Map.of("subnetId", "ocid1.subnet.oc1.iad.api", "isPublicIpEnabled", true, "nsgIds", List.of()),
                Map.of("kubernetesNetworkConfig", Map.of("podsCidr", "10.244.0.0/16", "servicesCidr", "10.96.0.0/16"),
                        "serviceLbSubnetIds", List.of("ocid1.subnet.oc1.iad.lb"),
                        "addOns", Map.of("isKubernetesDashboardEnabled", false, "isTillerEnabled", false),
                        "notAnOption", true),
                Map.of("isPolicyEnabled", false),
                List.of(Map.of("cniType", "FLANNEL_OVERLAY")));
    }

    @Test
    void createReadsBackTheSettingsAsSent() {
        StoredOkeCluster cluster = service.createCluster(COMPARTMENT, "settings", VCN, OkeVersions.DEFAULT_VERSION, null, null, null,
                fullSettings()).cluster();

        Map<String, Object> wire = service.getCluster(cluster.getId()).toWire();
        assertEquals("ENHANCED_CLUSTER", wire.get("type"));
        assertEquals(Map.of("subnetId", "ocid1.subnet.oc1.iad.api", "isPublicIpEnabled", true, "nsgIds", List.of()),
                wire.get("endpointConfig"));
        Map<?, ?> options = (Map<?, ?>) wire.get("options");
        assertEquals(Map.of("podsCidr", "10.244.0.0/16", "servicesCidr", "10.96.0.0/16"),
                options.get("kubernetesNetworkConfig"));
        assertFalse(options.containsKey("notAnOption"), "only ClusterCreateOptions fields are kept");
        assertEquals(Map.of("isPolicyEnabled", false), wire.get("imagePolicyConfig"));
        assertEquals(List.of(Map.of("cniType", "FLANNEL_OVERLAY")), wire.get("clusterPodNetworkOptions"));
        assertEquals(List.of(), wire.get("availableKubernetesUpgrades"));
        assertEquals(Map.of(), wire.get("systemTags"));
        assertEquals("127.0.0.1:6443", ((Map<?, ?>) wire.get("endpoints")).get("publicEndpoint"));
        assertNotNull(cluster.getEtag());
    }

    @Test
    void unsetSettingsStayAbsent() {
        StoredOkeCluster cluster = service.createCluster(COMPARTMENT, "bare", VCN, OkeVersions.DEFAULT_VERSION, null, null, null)
                .cluster();

        Map<String, Object> wire = cluster.toWire();
        for (String key : List.of("type", "endpointConfig", "options", "imagePolicyConfig", "clusterPodNetworkOptions")) {
            assertFalse(wire.containsKey(key), key + " is not invented");
        }
        assertNull(((Map<?, ?>) wire.get("endpoints")).get("publicEndpoint"));
    }

    @Test
    void createRejectsUnknownEnumValues() {
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> service.createCluster(COMPARTMENT,
                "bad", VCN, OkeVersions.DEFAULT_VERSION, null, null, null,
                new OkeService.ClusterSettings("HUGE_CLUSTER", null, null, null, null))).getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> service.createCluster(COMPARTMENT,
                "bad", VCN, OkeVersions.DEFAULT_VERSION, null, null, null,
                new OkeService.ClusterSettings(null, null, null, null, List.of(Map.of("cniType", "CALICO")))))
                .getCode());
        assertEquals("InvalidParameter", assertThrows(OciException.class, () -> service.createCluster(COMPARTMENT,
                "bad", VCN, OkeVersions.DEFAULT_VERSION, null, null, null,
                new OkeService.ClusterSettings(null, null, Map.of("ipFamilies", List.of("IPv5")), null, null)))
                .getCode());
    }

    @Test
    void updateChangesOnlyWhatUpdateClusterDetailsAllows() {
        StoredOkeCluster cluster = service.createCluster(COMPARTMENT, "upd", VCN, OkeVersions.DEFAULT_VERSION, null, null, null,
                fullSettings()).cluster();
        String etag = cluster.getEtag();

        service.updateCluster(cluster.getId(), null, null, Map.of("team", "a"), null,
                new OkeService.ClusterSettings(null, null,
                        Map.of("admissionControllerOptions", Map.of("isPodSecurityPolicyEnabled", false),
                                "kubernetesNetworkConfig", Map.of("podsCidr", "10.0.0.0/8")),
                        Map.of("isPolicyEnabled", true), null), etag);

        StoredOkeCluster updated = service.getCluster(cluster.getId());
        assertEquals(Map.of("team", "a"), updated.getFreeformTags());
        assertEquals(Map.of("isPolicyEnabled", true), updated.getImagePolicyConfig());
        assertEquals(Map.of("isPodSecurityPolicyEnabled", false),
                updated.getOptions().get("admissionControllerOptions"));
        assertEquals(Map.of("podsCidr", "10.244.0.0/16", "servicesCidr", "10.96.0.0/16"),
                updated.getOptions().get("kubernetesNetworkConfig"), "create-only options are kept");
        assertNotEquals(etag, updated.getEtag());
        assertEquals("NoEtagMatch", assertThrows(OciException.class, () -> service.updateCluster(cluster.getId(),
                "x", null, null, null, OkeService.ClusterSettings.NONE, etag)).getCode());
    }

    @Test
    void aClusterStoredWithoutAnEtagGetsOneAtStartup() {
        StoredOkeCluster cluster = service.createCluster(COMPARTMENT, "old", VCN, OkeVersions.DEFAULT_VERSION, null, null, null).cluster();
        cluster.setEtag(null);
        clusters.put(cluster.getId(), cluster);

        service.reconstructClusterState();

        String etag = service.getCluster(cluster.getId()).getEtag();
        assertNotNull(etag);
        service.updateCluster(cluster.getId(), "renamed", null, null, null, OkeService.ClusterSettings.NONE, etag);
        assertEquals("renamed", service.getCluster(cluster.getId()).getName());
    }

    @Test
    void concurrentUpdatesWithTheSameEtagLetOnlyOneThrough() throws Exception {
        StoredOkeCluster cluster = service.createCluster(COMPARTMENT, "race", VCN, OkeVersions.DEFAULT_VERSION, null, null, null)
                .cluster();
        String etag = cluster.getEtag();
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                String name = "writer-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.updateCluster(cluster.getId(), name, null, null, null,
                                OkeService.ClusterSettings.NONE, etag);
                        return true;
                    } catch (OciException e) {
                        assertEquals("NoEtagMatch", e.getCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            assertEquals(1, succeeded, "only the first write with the etag passes if-match");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void endpointConfigActionsKeepOrSetTheSubnet() {
        StoredOkeCluster withConfig = service.createCluster(COMPARTMENT, "ep", VCN, OkeVersions.DEFAULT_VERSION, null, null, null,
                fullSettings()).cluster();
        service.updateClusterEndpointConfig(withConfig.getId(),
                Map.of("isPublicIpEnabled", false, "subnetId", "ocid1.subnet.oc1.iad.other"), null);
        assertEquals(Map.of("subnetId", "ocid1.subnet.oc1.iad.api", "isPublicIpEnabled", false, "nsgIds", List.of()),
                service.getCluster(withConfig.getId()).getEndpointConfig());

        StoredOkeCluster bare = service.createCluster(COMPARTMENT, "ep2", VCN, OkeVersions.DEFAULT_VERSION, null, null, null).cluster();
        service.migrateClusterToNativeVcn(bare.getId(),
                Map.of("endpointConfig", Map.of("subnetId", "ocid1.subnet.oc1.iad.api")), null);
        assertEquals(Map.of("subnetId", "ocid1.subnet.oc1.iad.api"), service.getCluster(bare.getId()).getEndpointConfig());
        assertEquals("MissingParameter", assertThrows(OciException.class,
                () -> service.migrateClusterToNativeVcn(bare.getId(), Map.of(), null)).getCode());
    }

    @Test
    void updateAndDeleteCluster() {
        service.createCluster(COMPARTMENT, "cluster-1", VCN, "v1.35.2", null, null, null);
        StoredOkeCluster cluster = service.listClusters(COMPARTMENT).get(0);

        service.updateCluster(cluster.getId(), "updated-name", OkeVersions.DEFAULT_VERSION);
        StoredOkeCluster updated = service.getCluster(cluster.getId());
        assertEquals("updated-name", updated.getName());
        assertEquals(OkeVersions.DEFAULT_VERSION, updated.getKubernetesVersion());

        service.deleteCluster(cluster.getId());
        assertThrows(OciException.class, () -> service.getCluster(cluster.getId()));
    }

    @Test
    void deleteClusterDeletesDependentNodePools() {
        OkeService.CreateClusterResult cRes = service.createCluster(COMPARTMENT, "cluster-cascade", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);
        String clusterId = cRes.cluster().getId();

        OkeService.CreateNodePoolResult npRes = service.createNodePool(COMPARTMENT, clusterId, "pool-cascade", OkeVersions.DEFAULT_VERSION, "VM.Standard.E4.Flex", 2, null, null);
        String poolId = npRes.nodePool().getId();

        service.deleteCluster(clusterId);

        assertThrows(OciException.class, () -> service.getCluster(clusterId));
        assertThrows(OciException.class, () -> service.getNodePool(poolId));
        assertTrue(service.listNodePools(COMPARTMENT, clusterId).isEmpty());
    }

    @Test
    void createAndListNodePools() {
        service.createCluster(COMPARTMENT, "cluster-1", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);
        StoredOkeCluster cluster = service.listClusters(COMPARTMENT).get(0);

        service.createNodePool(COMPARTMENT, cluster.getId(), "pool-1", OkeVersions.DEFAULT_VERSION, "VM.Standard.E4.Flex", 2, null, null);
        List<StoredNodePool> pools = service.listNodePools(COMPARTMENT, cluster.getId());
        assertEquals(1, pools.size());
        StoredNodePool pool = pools.get(0);
        assertEquals("pool-1", pool.getName());
        assertEquals(cluster.getId(), pool.getClusterId());
        assertEquals(2, pool.getQuantityPerSubnet());
    }

    @Test
    void clearPurgesStorage() {
        service.createCluster(COMPARTMENT, "cluster-1", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);
        service.clear();
        assertTrue(service.listClusters(COMPARTMENT).isEmpty());
    }

    @Test
    void startupReconstructsActiveClusterState() {
        OkeClusterManager mockManager = mock(OkeClusterManager.class);
        EmulatorConfig mockConfig = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig mockServices = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServicesConfig.OkeServiceConfig mockOkeConfig = mock(EmulatorConfig.ServicesConfig.OkeServiceConfig.class);

        lenient().when(mockConfig.services()).thenReturn(mockServices);
        lenient().when(mockServices.oke()).thenReturn(mockOkeConfig);
        lenient().when(mockOkeConfig.mock()).thenReturn(false);

        OkeService persistentService = new OkeService(clusters, nodePools, mockConfig, null, mock(WorkRequestService.class), mockManager);

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.reconstruct");
        cluster.setHostPort(16443);
        clusters.put(cluster.getId(), cluster);

        persistentService.reconstructClusterState();

        org.mockito.Mockito.verify(mockManager).registerExistingCluster(cluster);
    }

    @Test
    void startupReconstructsActiveClusterStateAcrossTenancies() {
        OkeClusterManager mockManager = mock(OkeClusterManager.class);
        EmulatorConfig mockConfig = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig mockServices = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.ServicesConfig.OkeServiceConfig mockOkeConfig = mock(EmulatorConfig.ServicesConfig.OkeServiceConfig.class);

        lenient().when(mockConfig.services()).thenReturn(mockServices);
        lenient().when(mockServices.oke()).thenReturn(mockOkeConfig);
        lenient().when(mockOkeConfig.mock()).thenReturn(false);

        StorageBackend<String, StoredOkeCluster> rawBackend = new InMemoryStorage<>();
        TenancyAwareStorageBackend<StoredOkeCluster> taClusters = new TenancyAwareStorageBackend<>(rawBackend, null, "ocid1.tenancy.oc1..flocitesttenancy");

        OkeService persistentService = new OkeService(taClusters, nodePools, mockConfig, null, mock(WorkRequestService.class), mockManager);

        StoredOkeCluster otherTenancyCluster = new StoredOkeCluster();
        otherTenancyCluster.setId("ocid1.cluster.oc1.iad.othertenancy");
        otherTenancyCluster.setHostPort(16444);

        rawBackend.put("ocid1.tenancy.oc1..customtenancy/ocid1.cluster.oc1.iad.othertenancy", otherTenancyCluster);

        persistentService.reconstructClusterState();

        org.mockito.Mockito.verify(mockManager).registerExistingCluster(otherTenancyCluster);
    }

    @Test
    void storedOkeClusterPersistsHostPortToStorageAndExcludesFromWire() throws Exception {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.test1234");
        cluster.setName("test-cluster");
        cluster.setHostPort(16443);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(cluster);
        assertTrue(json.contains("\"hostPort\":16443"), "Jackson storage serialization must include hostPort");

        StoredOkeCluster deserialized = mapper.readValue(json, StoredOkeCluster.class);
        assertEquals(16443, deserialized.getHostPort(), "Jackson deserialization must restore hostPort");

        Map<String, Object> wire = cluster.toWire();
        assertFalse(wire.containsKey("hostPort"));
    }

    @Test
    void createClusterAssignsDistinctApiTokensKeptOffTheWire() {
        StoredOkeCluster first = service.createCluster(COMPARTMENT, "first", VCN, OkeVersions.DEFAULT_VERSION, null, null, null).cluster();
        StoredOkeCluster second = service.createCluster(COMPARTMENT, "second", VCN, OkeVersions.DEFAULT_VERSION, null, null, null).cluster();

        assertNotNull(first.getApiToken());
        assertEquals(43, first.getApiToken().length(), "32 random bytes, base64url without padding");
        assertNotEquals(first.getApiToken(), second.getApiToken());
        assertFalse(first.getApiToken().contains(first.getId()), "token must not be derivable from the OCID");
        assertFalse(first.toWire().containsKey("apiToken"));
        assertFalse(first.toWire().containsValue(first.getApiToken()));
    }

    @Test
    void startupBackfillsApiTokenForClustersStoredBeforeTheField() {
        StoredOkeCluster legacy = new StoredOkeCluster();
        legacy.setId("ocid1.cluster.oc1.iad.legacy");
        clusters.put(legacy.getId(), legacy);

        service.reconstructClusterState();

        String token = clusters.get(legacy.getId()).orElseThrow().getApiToken();
        assertNotNull(token);
        service.reconstructClusterState();
        assertEquals(token, clusters.get(legacy.getId()).orElseThrow().getApiToken(), "an existing token is never rotated");
    }

    @Test
    void startupBackfillsApiTokenInTheClusterOwnTenancy() {
        StorageBackend<String, StoredOkeCluster> rawBackend = new InMemoryStorage<>();
        TenancyAwareStorageBackend<StoredOkeCluster> taClusters =
                new TenancyAwareStorageBackend<>(rawBackend, null, "ocid1.tenancy.oc1..flocitesttenancy");
        OkeService tenancyService = new OkeService(taClusters, nodePools, null, null, mock(WorkRequestService.class), null);

        StoredOkeCluster legacy = new StoredOkeCluster();
        legacy.setId("ocid1.cluster.oc1.iad.legacyother");
        String rawKey = "ocid1.tenancy.oc1..customtenancy/ocid1.cluster.oc1.iad.legacyother";
        rawBackend.put(rawKey, legacy);

        tenancyService.reconstructClusterState();

        assertNotNull(rawBackend.get(rawKey).orElseThrow().getApiToken());
        assertEquals(1, rawBackend.keys().size(), "the backfill must not copy the cluster into another tenancy");
    }

    @Test
    void findClusterSearchesEveryRegionOfTheTenancy() {
        EmulatorConfig customRegion = mock(EmulatorConfig.class);
        lenient().when(customRegion.defaultRegion()).thenReturn("iad-local");
        StorageBackend<String, StoredOkeCluster> rawBackend = new InMemoryStorage<>();
        TenancyAwareStorageBackend<StoredOkeCluster> taClusters =
                new TenancyAwareStorageBackend<>(rawBackend, () -> TENANCY + ":iad-local");
        OkeService regionalService = new OkeService(taClusters, nodePools, customRegion, null,
                mock(WorkRequestService.class), null);
        StoredOkeCluster ashburn = new StoredOkeCluster();
        ashburn.setId("ocid1.cluster.oc1.iad.ashburn");
        StoredOkeCluster local = new StoredOkeCluster();
        local.setId("ocid1.cluster.oc1.iad.local");
        rawBackend.put(TENANCY + ":us-ashburn-1/" + ashburn.getId(), ashburn);
        rawBackend.put(TENANCY + ":iad-local/" + local.getId(), local);

        assertTrue(regionalService.findCluster(TENANCY, ashburn.getId()).isPresent(),
                "a cluster created through us-ashburn-1 shares the iad code with iad-local");
        assertTrue(regionalService.findCluster(TENANCY, local.getId()).isPresent());
        assertTrue(regionalService.findCluster("ocid1.tenancy.oc1..other", local.getId()).isEmpty());
    }

    // -- Real-mode readiness: CREATING + IN_PROGRESS until the k3s API answers --

    private static final String TENANCY = "ocid1.tenancy.oc1..readinesstenancy";

    private OkeClusterManager readinessManager;
    private WorkRequestService realWorkRequests;
    private MutableClock clock;

    private OkeService realModeService() {
        EmulatorConfig realConfig = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(realConfig.defaultRealm()).thenReturn("oc1");
        lenient().when(realConfig.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(realConfig.services().oke().mock()).thenReturn(false);
        lenient().when(realConfig.services().oke().readyTimeoutSeconds()).thenReturn(300);
        lenient().when(realConfig.services().oke().maxNodesPerCluster()).thenReturn(3);
        lenient().when(realConfig.services().oke().loadBalancer().mode()).thenReturn("emulated");
        readinessManager = mock(OkeClusterManager.class);
        realWorkRequests = new WorkRequestService(new InMemoryStorage<>(), realConfig);
        clock = new MutableClock(Instant.parse("2026-10-01T12:00:00Z"));
        return new OkeService(clusters, nodePools, realConfig, null, realWorkRequests, readinessManager,
                () -> TENANCY, clock, OciContext.fromConfig(realConfig), null);
    }

    @Test
    void realModeCreateStaysCreatingWithAnInProgressWorkRequestNamingTheCluster() {
        OkeService service = realModeService();

        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "pending", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);

        StoredOkeCluster cluster = result.cluster();
        assertEquals("CREATING", cluster.getLifecycleState());
        assertEquals(TENANCY, cluster.getTenancyId());
        assertEquals("us-ashburn-1", cluster.getRegion());
        assertEquals(result.workRequestId(), cluster.getCreateWorkRequestId());
        StoredWorkRequest workRequest = realWorkRequests.get(result.workRequestId());
        assertEquals("IN_PROGRESS", workRequest.getStatus());
        assertNull(workRequest.getTimeFinished(), "Terraform keeps polling only while timeFinished is unset");
        assertEquals(cluster.getId(), workRequest.getResources().get(0).getIdentifier(),
                "Terraform reads the cluster id from the first work-request read");
        assertFalse(cluster.toWire().containsKey("tenancyId"));
        assertFalse(cluster.toWire().containsKey("createWorkRequestId"));
    }

    @Test
    void pollReadinessActivatesTheClusterAndFinishesTheWorkRequest() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "ready", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);
        when(readinessManager.probeReady(any())).thenReturn(Optional.of("Q0FEQVRB"));

        service.pollReadiness();

        StoredOkeCluster cluster = service.getCluster(result.cluster().getId());
        assertEquals("ACTIVE", cluster.getLifecycleState());
        assertEquals("Q0FEQVRB", cluster.getCaCertificate());
        assertFalse(cluster.toWire().containsKey("caCertificate"));
        StoredWorkRequest workRequest = realWorkRequests.get(result.workRequestId());
        assertEquals("SUCCEEDED", workRequest.getStatus());
        assertEquals(100.0f, workRequest.getPercentComplete().floatValue());
        assertNotNull(workRequest.getTimeFinished());
    }

    @Test
    void pollReadinessKeepsWaitingWhileTheSidecarRunsWithinTheTimeout() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "booting", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);
        when(readinessManager.probeReady(any())).thenReturn(Optional.empty());
        when(readinessManager.isClusterRunning(any())).thenReturn(true);
        clock.advanceSeconds(299);

        service.pollReadiness();

        assertEquals("CREATING", service.getCluster(result.cluster().getId()).getLifecycleState());
        assertEquals("IN_PROGRESS", realWorkRequests.get(result.workRequestId()).getStatus());
    }

    @Test
    void pollReadinessDoesNotResurrectAClusterDeletedDuringTheProbe() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "doomed", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);
        String clusterId = result.cluster().getId();
        when(readinessManager.probeReady(any())).thenAnswer(invocation -> {
            service.deleteCluster(clusterId);
            return Optional.empty();
        });
        when(readinessManager.isClusterRunning(any())).thenReturn(false);

        service.pollReadiness();

        assertEquals(404, assertThrows(OciException.class, () -> service.getCluster(clusterId)).getHttpStatus());
        assertEquals("CANCELED", realWorkRequests.get(result.workRequestId()).getStatus());
    }

    @Test
    void pollReadinessFailsTheClusterWhenTheSidecarExits() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "crashed", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);
        when(readinessManager.probeReady(any())).thenReturn(Optional.empty());
        when(readinessManager.isClusterRunning(any())).thenReturn(false);

        service.pollReadiness();

        StoredOkeCluster cluster = service.getCluster(result.cluster().getId());
        assertEquals("FAILED", cluster.getLifecycleState());
        assertNotNull(cluster.getLifecycleDetails());
        StoredWorkRequest workRequest = realWorkRequests.get(result.workRequestId());
        assertEquals("FAILED", workRequest.getStatus());
        assertNotNull(workRequest.getTimeFinished());
        assertEquals(1, workRequest.getErrors().size(), "Terraform reads the failure from the work request's errors");
        assertEquals("InternalServerError", workRequest.getErrors().get(0).getCode());
        assertEquals(cluster.getLifecycleDetails(), workRequest.getErrors().get(0).getMessage());
    }

    @Test
    void aSucceededCreateHasNoWorkRequestErrors() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "fine", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);
        when(readinessManager.probeReady(any())).thenReturn(Optional.of("Q0FEQVRB"));

        service.pollReadiness();

        assertTrue(realWorkRequests.get(result.workRequestId()).getErrors().isEmpty());
    }

    @Test
    void aPollWhileTheUpgradeReplacesTheServerDoesNotFailTheCluster() {
        OkeService service = realModeService();
        String clusterId = service.createCluster(COMPARTMENT, "replacing", VCN, "v1.35.2", null, null, null)
                .cluster().getId();
        when(readinessManager.probeReady(any())).thenReturn(Optional.of("Q0FEQVRB"));
        service.pollReadiness();
        when(readinessManager.probeReady(any())).thenReturn(Optional.empty());
        when(readinessManager.isClusterRunning(any())).thenReturn(false);
        org.mockito.Mockito.doAnswer(i -> {
            service.pollReadiness();
            return null;
        }).when(readinessManager).restartCluster(any());

        service.updateCluster(clusterId, null, "v1.36.4", null, null, OkeService.ClusterSettings.NONE, null);

        assertEquals("UPDATING", service.getCluster(clusterId).getLifecycleState(),
                "the old server is gone and the new one not started yet while the image is pulled");
    }

    @Test
    void anUpgradeRecreatesTheServerAndStaysUpdatingUntilItAnswers() {
        OkeService service = realModeService();
        String clusterId = service.createCluster(COMPARTMENT, "upgrading", VCN, "v1.35.2", null, null, null)
                .cluster().getId();
        when(readinessManager.probeReady(any())).thenReturn(Optional.of("Q0FEQVRB"));
        service.pollReadiness();

        when(readinessManager.probeReady(any())).thenReturn(Optional.empty());
        when(readinessManager.isClusterRunning(any())).thenReturn(true);
        String workRequestId = service.updateCluster(clusterId, null, "v1.36.4", null, null,
                OkeService.ClusterSettings.NONE, null);

        verify(readinessManager).restartCluster(argThat(c -> "v1.36.4".equals(c.getKubernetesVersion())));
        assertEquals("UPDATING", service.getCluster(clusterId).getLifecycleState());
        assertEquals("CLUSTER_UPDATE", realWorkRequests.get(workRequestId).getOperationType());
        assertEquals("IN_PROGRESS", realWorkRequests.get(workRequestId).getStatus());
        OciException busy = assertThrows(OciException.class, () -> service.updateCluster(clusterId, null, "v1.37.0",
                null, null, OkeService.ClusterSettings.NONE, null));
        assertEquals("IncorrectState", busy.getCode());

        service.pollReadiness();
        assertEquals("UPDATING", service.getCluster(clusterId).getLifecycleState());
        when(readinessManager.probeReady(any())).thenReturn(Optional.of("Q0FEQVRB"));
        service.pollReadiness();

        assertEquals("ACTIVE", service.getCluster(clusterId).getLifecycleState());
        assertEquals("SUCCEEDED", realWorkRequests.get(workRequestId).getStatus());
    }

    private String activeRealCluster(OkeService service, String name) {
        String clusterId = service.createCluster(COMPARTMENT, name, VCN, OkeVersions.DEFAULT_VERSION, null, null, null)
                .cluster().getId();
        when(readinessManager.probeReady(any())).thenReturn(Optional.of("Q0FEQVRB"));
        service.pollReadiness();
        lenient().when(readinessManager.readJoinToken(any())).thenReturn(Optional.of("K10join::server:secret"));
        lenient().when(readinessManager.nodeContainerName(any())).thenAnswer(i -> "agent-" + ((StoredNode) i.getArgument(0)).getId());
        lenient().when(readinessManager.isClusterRunning(any())).thenReturn(true);
        return clusterId;
    }

    private static OkeService.NodePoolSettings placedIn(int size) {
        return new OkeService.NodePoolSettings(null, Map.of("size", size, "placementConfigs", List.of(
                Map.of("availabilityDomain", "Floc:US-ASHBURN-AD-1", "subnetId", "ocid1.subnet.oc1.iad.workers"))),
                null, null, null, null, null, null, null);
    }

    @Test
    void aRealNodePoolIsCreatingUntilItsAgentsAreReadyThenActive() {
        OkeService service = realModeService();
        String clusterId = activeRealCluster(service, "agents");
        when(readinessManager.nodeReadiness(any())).thenReturn(Optional.of(Map.of()));

        OkeService.CreateNodePoolResult created = service.createNodePool(COMPARTMENT, clusterId, "workers", null,
                "VM.Standard.E4.Flex", null, null, null, placedIn(2));
        String poolId = created.nodePool().getId();
        assertEquals("CREATING", service.getNodePool(poolId).getLifecycleState());
        assertEquals("IN_PROGRESS", realWorkRequests.get(created.workRequestId()).getStatus());

        service.pollReadiness();
        verify(readinessManager, times(2)).ensureNode(any(), any(), any());
        assertEquals("CREATING", service.getNodePool(poolId).getLifecycleState());

        List<String> addresses = service.getNodePool(poolId).getNodes().stream().map(StoredNode::getPrivateIp).toList();
        when(readinessManager.nodeReadiness(any())).thenReturn(Optional.of(Map.of(
                addresses.get(0), true, addresses.get(1), true, "floci-oci-oke-server", false)));
        when(readinessManager.finishNode(any(), any(), any())).thenReturn(true);
        service.pollReadiness();

        StoredNodePool pool = service.getNodePool(poolId);
        assertEquals("ACTIVE", pool.getLifecycleState());
        assertTrue(pool.getNodes().stream().allMatch(node -> "ACTIVE".equals(node.getLifecycleState()) && node.isRunning()));
        assertEquals("SUCCEEDED", realWorkRequests.get(created.workRequestId()).getStatus());
        verify(readinessManager, atLeastOnce()).deleteStaleNodes(any(), argThat(expected -> expected.containsAll(addresses)
                && expected.size() == 2), any());
        assertFalse(pool.toWire().toString().contains("agent-"), "container names are internal");
    }

    @Test
    void nodesPastTheCapExistInTheApiWithoutAnAgent() {
        OkeService service = realModeService();
        String clusterId = activeRealCluster(service, "capped");
        when(readinessManager.nodeReadiness(any())).thenAnswer(i -> {
            Map<String, Boolean> ready = new java.util.HashMap<>();
            service.listNodePools(null, clusterId).forEach(p -> p.getNodes().forEach(n -> ready.put(n.getPrivateIp(), true)));
            return Optional.of(ready);
        });
        when(readinessManager.finishNode(any(), any(), any())).thenReturn(true);

        String first = service.createNodePool(COMPARTMENT, clusterId, "first", null, "VM.Standard.E4.Flex", null,
                null, null, placedIn(2)).nodePool().getId();
        service.pollReadiness();
        String second = service.createNodePool(COMPARTMENT, clusterId, "second", null, "VM.Standard.E4.Flex", null,
                null, null, placedIn(2)).nodePool().getId();
        service.pollReadiness();

        assertEquals(List.of(true, true), service.getNodePool(first).getNodes().stream().map(StoredNode::isRunning).toList());
        StoredNodePool capped = service.getNodePool(second);
        assertEquals(2, capped.getNodes().size(), "the API keeps the requested count");
        assertEquals(List.of(true, false), capped.getNodes().stream().map(StoredNode::isRunning).toList());
        assertTrue(capped.getNodes().get(1).getLifecycleDetails().contains("max-nodes-per-cluster"));
        assertEquals("ACTIVE", capped.getLifecycleState());
        String cappedNode = capped.getNodes().get(1).getId();
        verify(readinessManager, never()).ensureNode(any(), any(), argThat(node -> node.getId().equals(cappedNode)));

        service.updateNodePool(first, null, null, null, null, null, null,
                new OkeService.NodePoolSettings(null, Map.of("size", 1), null, null, null, null, null, null, null));
        verify(readinessManager).removeNode(any(), argThat(node -> node.getPrivateIp().endsWith(".17")), eq(true));
        service.pollReadiness();
        assertTrue(service.getNodePool(second).getNodes().get(1).isRunning(), "a freed slot goes to the next pool");
    }

    @Test
    void aNodeAddedWhileAReconcilePassRunsWaitsForTheNextPass() {
        OkeService service = realModeService();
        String clusterId = activeRealCluster(service, "racing");
        when(readinessManager.nodeReadiness(any())).thenAnswer(i -> {
            Map<String, Boolean> ready = new java.util.HashMap<>();
            service.listNodePools(null, clusterId).forEach(p -> p.getNodes().forEach(n -> ready.put(n.getPrivateIp(), true)));
            return Optional.of(ready);
        });
        when(readinessManager.finishNode(any(), any(), any())).thenReturn(true);
        String poolId = service.createNodePool(COMPARTMENT, clusterId, "racing", null, "VM.Standard.E4.Flex", null,
                null, null, placedIn(1)).nodePool().getId();
        service.pollReadiness();
        assertEquals("ACTIVE", service.getNodePool(poolId).getLifecycleState());

        when(readinessManager.ensureNode(any(), any(), any())).thenAnswer(i -> {
            if (service.getNodePool(poolId).getNodes().size() == 1) {
                service.updateNodePool(poolId, null, null, null, null, null, null,
                        new OkeService.NodePoolSettings(null, Map.of("size", 2), null, null, null, null, null, null, null));
            }
            return false;
        });
        service.reconcile(TENANCY, clusterId);

        StoredNodePool pool = service.getNodePool(poolId);
        StoredNode added = pool.getNodes().get(1);
        assertEquals("CREATING", added.getLifecycleState(), "a node the pass did not start is not past the cap");
        assertNull(added.getLifecycleDetails());
        assertEquals("UPDATING", pool.getLifecycleState());

        service.pollReadiness();
        verify(readinessManager).ensureNode(any(), any(), argThat(node -> node.getId().equals(added.getId())));
        assertEquals("ACTIVE", service.getNodePool(poolId).getLifecycleState());
    }

    @Test
    void reconcileRemovesAgentsNoNodeOfTheClusterHas() {
        OkeService service = realModeService();
        String clusterId = activeRealCluster(service, "orphans");
        when(readinessManager.nodeReadiness(any())).thenReturn(Optional.of(Map.of()));
        StoredNodePool pool = service.createNodePool(COMPARTMENT, clusterId, "kept", null, "VM.Standard.E4.Flex",
                null, null, null, placedIn(1)).nodePool();
        String nodeId = pool.getNodes().getFirst().getId();

        service.pollReadiness();

        verify(readinessManager, atLeastOnce()).removeOrphanAgents(argThat(c -> c.getId().equals(clusterId)),
                argThat(ids -> ids.equals(java.util.Set.of(nodeId))));
    }

    @Test
    void restoreRecreatesTheAgentsOfStoredNodePoolsOnceTheServerAnswers() {
        OkeService service = realModeService();
        String clusterId = activeRealCluster(service, "restored");
        StoredOkeCluster cluster = service.getCluster(clusterId);
        cluster.setCaCertificate("Q0FEQVRB");
        StoredNodePool legacy = new StoredNodePool();
        legacy.setId("ocid1.nodepool.oc1.iad.legacy");
        legacy.setClusterId(clusterId);
        legacy.setCompartmentId(COMPARTMENT);
        legacy.setName("legacy");
        legacy.setNodeShape("VM.Standard2.1");
        legacy.setKubernetesVersion(OkeVersions.DEFAULT_VERSION);
        legacy.setLifecycleState("ACTIVE");
        legacy.setNodeConfigDetails(new java.util.LinkedHashMap<>(Map.of("size", 1, "placementConfigs", List.of(
                Map.of("availabilityDomain", "Floc:US-ASHBURN-AD-1", "subnetId", "ocid1.subnet.oc1.iad.workers")))));
        nodePools.put(legacy.getId(), legacy);
        service.updateNodePool(legacy.getId(), null, null, null);
        when(readinessManager.nodeReadiness(any())).thenReturn(Optional.of(Map.of()));
        org.mockito.Mockito.clearInvocations(readinessManager);

        service.reconstructClusterState();
        service.pollReadiness();

        verify(readinessManager).registerExistingCluster(argThat(c -> c.getId().equals(clusterId)));
        verify(readinessManager, atLeastOnce()).ensureNode(any(), argThat(p -> p.getId().equals(legacy.getId())), any());
        assertEquals(TENANCY, service.getNodePool(legacy.getId()).getTenancyId(), "pools stored before get their tenancy");
    }

    @Test
    void aNewNodePoolVersionReachesRunningNodesOnlyThroughCycling() {
        String clusterId = service.createCluster(COMPARTMENT, "cycling", VCN, "v1.36.4", null, null, null)
                .cluster().getId();
        StoredNodePool pool = service.createNodePool(COMPARTMENT, clusterId, "cycled", "v1.35.2", "VM.Standard2.1",
                null, null, null, placedIn(2)).nodePool();
        List<String> originalIds = pool.getNodes().stream().map(StoredNode::getId).toList();

        service.updateNodePool(pool.getId(), null, "v1.35.2", null);
        service.updateNodePool(pool.getId(), null, "v1.36.4", null);
        assertEquals(List.of("v1.35.2", "v1.35.2"), service.getNodePool(pool.getId()).getNodes().stream()
                .map(StoredNode::getKubernetesVersion).toList(), "running nodes keep their version without cycling");

        service.updateNodePool(pool.getId(), null, null, null, null, null, null, new OkeService.NodePoolSettings(
                null, Map.of("size", 3), null, null, null, null, null, null, null));
        assertEquals("v1.36.4", service.getNodePool(pool.getId()).getNodes().get(2).getKubernetesVersion(),
                "a new node takes the pool's version");

        String bootVolumeClusterId = service.createCluster(COMPARTMENT, "bootvolume", VCN, "v1.36.4", null, null, null)
                .cluster().getId();
        StoredNodePool kept = service.createNodePool(COMPARTMENT, bootVolumeClusterId, "kept", "v1.35.2",
                "VM.Standard2.1", null, null, null, placedIn(1)).nodePool();
        String keptId = kept.getNodes().getFirst().getId();
        service.updateNodePool(kept.getId(), null, "v1.36.4", null, null, null, null, new OkeService.NodePoolSettings(
                null, null, null, null, null, null, null,
                Map.of("isNodeCyclingEnabled", true, "cycleModes", List.of("BOOT_VOLUME_REPLACE")), null));
        assertEquals(keptId, service.getNodePool(kept.getId()).getNodes().getFirst().getId());
        assertEquals("v1.36.4", service.getNodePool(kept.getId()).getNodes().getFirst().getKubernetesVersion());

        StoredNodePool replaced = service.createNodePool(COMPARTMENT, bootVolumeClusterId, "replaced", "v1.35.2",
                "VM.Standard2.1", null, null, null, placedIn(2)).nodePool();
        List<String> replacedAddresses = replaced.getNodes().stream().map(StoredNode::getPrivateIp).toList();
        service.updateNodePool(replaced.getId(), null, "v1.36.4", null, null, null, null,
                new OkeService.NodePoolSettings(null, null, null, null, null, null, null,
                        Map.of("isNodeCyclingEnabled", true), null));
        List<StoredNode> after = service.getNodePool(replaced.getId()).getNodes();
        assertEquals(2, after.size());
        assertTrue(after.stream().allMatch(node -> "v1.36.4".equals(node.getKubernetesVersion())
                && !originalIds.contains(node.getId())));
        assertTrue(after.stream().noneMatch(node -> replacedAddresses.contains(node.getPrivateIp())),
                "a replacement instance gets a new address, so its node is not mistaken for the old one");
    }

    @Test
    void anEmptyRealNodePoolIsActiveAtOnce() {
        OkeService service = realModeService();
        String clusterId = activeRealCluster(service, "empty");

        OkeService.CreateNodePoolResult created = service.createNodePool(COMPARTMENT, clusterId, "empty", null,
                "VM.Standard.E4.Flex", null, null, null, OkeService.NodePoolSettings.NONE);

        assertEquals("ACTIVE", created.nodePool().getLifecycleState());
        assertEquals("SUCCEEDED", realWorkRequests.get(created.workRequestId()).getStatus());
    }

    @Test
    void aNodePoolNeedsAnActiveCluster() {
        OkeService service = realModeService();
        String creating = service.createCluster(COMPARTMENT, "creating", VCN, OkeVersions.DEFAULT_VERSION, null, null, null).cluster().getId();

        OciException notYet = assertThrows(OciException.class, () -> service.createNodePool(COMPARTMENT, creating,
                "early", OkeVersions.DEFAULT_VERSION, "VM.Standard.E4.Flex", 1, null, null));
        assertEquals(409, notYet.getHttpStatus());
        assertEquals("IncorrectState", notYet.getCode(), "retryable: the cluster may still become ACTIVE");

        when(readinessManager.probeReady(any())).thenReturn(Optional.empty());
        when(readinessManager.isClusterRunning(any())).thenReturn(false);
        service.pollReadiness();
        OciException never = assertThrows(OciException.class, () -> service.createNodePool(COMPARTMENT, creating,
                "late", OkeVersions.DEFAULT_VERSION, "VM.Standard.E4.Flex", 1, null, null));
        assertEquals(409, never.getHttpStatus());
        assertEquals("Conflict", never.getCode(), "not retryable: a FAILED cluster stays FAILED");

        String active = service.createCluster(COMPARTMENT, "active", VCN, OkeVersions.DEFAULT_VERSION, null, null, null).cluster().getId();
        when(readinessManager.probeReady(any())).thenReturn(Optional.of("Q0FEQVRB"));
        service.pollReadiness();
        assertEquals("ACTIVE", service.createNodePool(COMPARTMENT, active, "pool", OkeVersions.DEFAULT_VERSION, "VM.Standard.E4.Flex",
                1, null, null).nodePool().getLifecycleState());
    }

    @Test
    void pollReadinessFailsTheClusterAfterTheReadyTimeout() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "slow", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);
        when(readinessManager.probeReady(any())).thenReturn(Optional.empty());
        when(readinessManager.isClusterRunning(any())).thenReturn(true);
        clock.advanceSeconds(301);

        service.pollReadiness();

        assertEquals("FAILED", service.getCluster(result.cluster().getId()).getLifecycleState());
        assertEquals("FAILED", realWorkRequests.get(result.workRequestId()).getStatus());
    }

    @Test
    void oneClusterFailingToProbeDoesNotStarveTheOthers() {
        OkeService service = realModeService();
        StoredOkeCluster broken = service.createCluster(COMPARTMENT, "broken", VCN, OkeVersions.DEFAULT_VERSION, null, null, null).cluster();
        StoredOkeCluster healthy = service.createCluster(COMPARTMENT, "healthy", VCN, OkeVersions.DEFAULT_VERSION, null, null, null).cluster();
        when(readinessManager.probeReady(any())).thenAnswer(invocation -> {
            StoredOkeCluster probed = invocation.getArgument(0);
            if (probed.getId().equals(broken.getId())) {
                throw new IllegalArgumentException("unsupported URI");
            }
            return Optional.of("Q0FEQVRB");
        });

        service.pollReadiness();

        assertEquals("ACTIVE", service.getCluster(healthy.getId()).getLifecycleState());
        assertEquals("CREATING", service.getCluster(broken.getId()).getLifecycleState());
    }

    @Test
    void deletingACreatingClusterCancelsItsWorkRequest() {
        OkeService service = realModeService();
        OkeService.CreateClusterResult result = service.createCluster(COMPARTMENT, "doomed", VCN, OkeVersions.DEFAULT_VERSION, null, null, null);

        service.deleteCluster(result.cluster().getId());
        service.pollReadiness();

        assertEquals("CANCELED", realWorkRequests.get(result.workRequestId()).getStatus());
    }

    @Test
    void restartKeepsReadyClustersActiveAndPollsTheOthersAgain() {
        OkeService service = realModeService();
        StoredOkeCluster ready = storedCluster("ocid1.cluster.oc1.iad.restartready", "Q0FEQVRB");
        StoredOkeCluster unready = storedCluster("ocid1.cluster.oc1.iad.restartunready", null);
        when(readinessManager.probeReady(any())).thenReturn(Optional.of("TkVXQ0E="));

        service.reconstructClusterState();
        assertEquals("ACTIVE", service.getCluster(ready.getId()).getLifecycleState());
        assertEquals("CREATING", service.getCluster(unready.getId()).getLifecycleState());

        service.pollReadiness();
        assertEquals("ACTIVE", service.getCluster(unready.getId()).getLifecycleState());
        assertEquals("TkVXQ0E=", service.getCluster(unready.getId()).getCaCertificate());
    }

    @Test
    void restartRecapturesTheCaWhenShutdownPrunedTheVolumes() {
        OkeService service = realModeService();
        StoredOkeCluster ready = storedCluster("ocid1.cluster.oc1.iad.restartpruned", "T0xEQ0E=");
        when(readinessManager.prunesVolumesOnStop()).thenReturn(true);
        when(readinessManager.probeReady(any())).thenReturn(Optional.of("TkVXQ0E="));

        service.reconstructClusterState();
        assertEquals("CREATING", service.getCluster(ready.getId()).getLifecycleState());

        service.pollReadiness();
        assertEquals("ACTIVE", service.getCluster(ready.getId()).getLifecycleState());
        assertEquals("TkVXQ0E=", service.getCluster(ready.getId()).getCaCertificate());
    }

    // ── Load balancer records (real mode) ──────────────────────────────────

    private OkeLoadBalancerAppliances lbAppliances;
    private InMemoryStorage<String, StoredLoadBalancer> loadBalancers;

    private OkeService loadBalancerService() {
        EmulatorConfig realConfig = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(realConfig.defaultRealm()).thenReturn("oc1");
        lenient().when(realConfig.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(realConfig.services().oke().mock()).thenReturn(false);
        lenient().when(realConfig.services().oke().readyTimeoutSeconds()).thenReturn(300);
        lenient().when(realConfig.services().oke().maxNodesPerCluster()).thenReturn(5);
        lenient().when(realConfig.services().oke().loadBalancer().mode()).thenReturn("emulated");
        lenient().when(realConfig.services().oke().loadBalancer().publicCidr()).thenReturn("192.0.2.0/24");
        lenient().when(realConfig.services().oke().loadBalancer().provisioningDelaySeconds()).thenReturn(0);
        readinessManager = mock(OkeClusterManager.class);
        lbAppliances = mock(OkeLoadBalancerAppliances.class);
        lenient().when(lbAppliances.nameFor(any())).thenAnswer(invocation -> "lb-" + invocation.getArgument(0));
        lenient().when(lbAppliances.ensure(any(), any())).thenReturn(Map.of("TCP/80", 41080));
        loadBalancers = new InMemoryStorage<>();
        realWorkRequests = new WorkRequestService(new InMemoryStorage<>(), realConfig);
        clock = new MutableClock(Instant.parse("2026-10-01T12:00:00Z"));
        return new OkeService(clusters, nodePools, loadBalancers, realConfig, null, realWorkRequests, readinessManager,
                lbAppliances, () -> TENANCY, clock, OciContext.fromConfig(realConfig), null);
    }

    private StoredOkeCluster activeEmulatedCluster(String id) {
        StoredOkeCluster cluster = storedCluster(id, "Q0E=");
        cluster.setTenancyId(TENANCY);
        cluster.setRegion("us-ashburn-1");
        cluster.setCompartmentId(COMPARTMENT);
        cluster.setKubernetesVersion(OkeVersions.DEFAULT_VERSION);
        cluster.setNodeSubnet("10.0.10.0/24");
        cluster.setLoadBalancerMode("emulated");
        cluster.setApiToken("token");
        clusters.put(id, cluster);
        return cluster;
    }

    @Test
    void aStoredLoadBalancerKeepsItsAddressAndGetsItsApplianceBackAfterARestart() {
        OkeService service = loadBalancerService();
        StoredOkeCluster cluster = activeEmulatedCluster("ocid1.cluster.oc1.iad.lbrestart");
        StoredLoadBalancer stored = new StoredLoadBalancer();
        stored.setClusterId(cluster.getId());
        stored.setServiceUid("uid-1");
        stored.setNamespace("default");
        stored.setName("web");
        stored.setKind("lb");
        stored.setPublicIp("192.0.2.42");
        stored.setApplianceName("lb-uid-1");
        stored.setHostPorts(Map.of("TCP/80", 41080));
        stored.setFirstSeen("2026-10-01T11:00:00Z");
        stored.setEnsured(true);
        loadBalancers.put(cluster.getId() + "/uid-1", stored);
        ObjectNode web = OkeLoadBalancerSpecTest.service(Map.of(
                OkeLoadBalancerSpec.HOST_PORTS_ANNOTATION, "TCP/80=127.0.0.1:41080"));
        ((ObjectNode) web.get("metadata")).putArray("finalizers").add(OkeLoadBalancerSpec.CLEANUP_FINALIZER);
        web.putObject("status").putObject("loadBalancer").putArray("ingress").addObject()
                .put("ip", "192.0.2.42").put("ipMode", "VIP");
        when(readinessManager.snapshot(any())).thenReturn(Optional.of(
                new OkeLoadBalancerKube.Snapshot(List.of(web), List.of(), List.of())));

        service.reconstructClusterState();
        service.pollLoadBalancers();

        verify(lbAppliances).ensure(any(), argThat(spec -> "192.0.2.42".equals(spec.publicIp())
                && "lb-uid-1".equals(spec.name()) && Map.of("TCP/80", 41080).equals(spec.preferredHostPorts())));
        verify(readinessManager, never()).patchServiceStatus(any(), any(), any(), any());
        assertEquals("192.0.2.42", service.loadBalancerRecords(cluster).getFirst().getPublicIp());
    }

    @Test
    void publicAddressesAreUniqueAcrossClustersAndAReservationIsKept() {
        OkeService service = loadBalancerService();
        StoredOkeCluster first = activeEmulatedCluster("ocid1.cluster.oc1.iad.lbfirst");
        StoredOkeCluster second = activeEmulatedCluster("ocid1.cluster.oc1.iad.lbsecond");
        OkeLoadBalancerSpec spec = OkeLoadBalancerSpec.from(OkeLoadBalancerSpecTest.service(Map.of()));

        OkeLoadBalancerRecords.Reservation a = service.reserve(first, spec, clock.instant());
        OkeLoadBalancerRecords.Reservation b = service.reserve(second, spec, clock.instant());
        OkeLoadBalancerRecords.Reservation again = service.reserve(first, spec, clock.instant());

        assertEquals("192.0.2.10", a.record().getPublicIp());
        assertEquals("192.0.2.11", b.record().getPublicIp());
        assertTrue(a.changed());
        assertFalse(again.changed());
        assertEquals("192.0.2.10", again.record().getPublicIp());
    }

    @Test
    void nodesAndInternalLoadBalancersNeverShareAnAddress() {
        OkeService service = loadBalancerService();
        StoredOkeCluster cluster = activeEmulatedCluster("ocid1.cluster.oc1.iad.lbnodes");
        OkeLoadBalancerSpec internal = OkeLoadBalancerSpec.from(OkeLoadBalancerSpecTest.service(Map.of(
                "service.beta.kubernetes.io/oci-load-balancer-internal", "true")));
        assertEquals("10.0.10.254", service.reserve(cluster, internal, clock.instant()).record().getPrivateIp());
        StoredLoadBalancer low = new StoredLoadBalancer();
        low.setClusterId(cluster.getId());
        low.setServiceUid("uid-low");
        low.setKind("nlb");
        low.setInternal(true);
        low.setPrivateIp("10.0.10.16");
        low.setFirstSeen(clock.instant().toString());
        loadBalancers.put(cluster.getId() + "/uid-low", low);

        StoredNodePool pool = service.createNodePool(COMPARTMENT, cluster.getId(), "workers", null,
                "VM.Standard.E4.Flex", null, null, null, placedIn(2)).nodePool();

        assertEquals(List.of("10.0.10.17", "10.0.10.18"),
                pool.getNodes().stream().map(StoredNode::getPrivateIp).toList());
    }

    @Test
    void anAddressTheServiceAsksForMustBeFreeAndInRange() {
        OkeService service = loadBalancerService();
        StoredOkeCluster cluster = activeEmulatedCluster("ocid1.cluster.oc1.iad.lbrequested");
        ObjectNode outside = OkeLoadBalancerSpecTest.service(Map.of());
        ((ObjectNode) outside.get("spec")).put("loadBalancerIP", "198.51.100.7");
        ObjectNode reserved = OkeLoadBalancerSpecTest.service(Map.of());
        ((ObjectNode) reserved.get("spec")).put("loadBalancerIP", "192.0.2.50");
        ObjectNode same = OkeLoadBalancerSpecTest.service(Map.of());
        ((ObjectNode) same.get("spec")).put("loadBalancerIP", "192.0.2.50");
        ((ObjectNode) same.get("metadata")).put("uid", "uid-2");
        ObjectNode assigned = OkeLoadBalancerSpecTest.service(Map.of(
                "oci.oraclecloud.com/load-balancer-type", "nlb",
                "oci-network-load-balancer.oraclecloud.com/internal", "true",
                "oci-network-load-balancer.oraclecloud.com/assigned-private-ipv4", "10.0.10.100"));
        ((ObjectNode) assigned.get("metadata")).put("uid", "uid-3");

        assertEquals("loadBalancerIP 198.51.100.7 is not a free address of floci-oci's public load balancer range"
                + " 192.0.2.0/24", assertThrows(IllegalStateException.class,
                () -> service.reserve(cluster, OkeLoadBalancerSpec.from(outside), clock.instant())).getMessage());
        assertEquals("192.0.2.50",
                service.reserve(cluster, OkeLoadBalancerSpec.from(reserved), clock.instant()).record().getPublicIp());
        assertThrows(IllegalStateException.class,
                () -> service.reserve(cluster, OkeLoadBalancerSpec.from(same), clock.instant()));
        assertEquals("10.0.10.100",
                service.reserve(cluster, OkeLoadBalancerSpec.from(assigned), clock.instant()).record().getPrivateIp());
    }

    @Test
    void changingAServiceToInternalMovesItsLoadBalancerToAPrivateAddress() {
        OkeService service = loadBalancerService();
        StoredOkeCluster cluster = activeEmulatedCluster("ocid1.cluster.oc1.iad.lbtoggle");
        service.reserve(cluster, OkeLoadBalancerSpec.from(OkeLoadBalancerSpecTest.service(Map.of())), clock.instant());

        OkeLoadBalancerRecords.Reservation internal = service.reserve(cluster, OkeLoadBalancerSpec.from(
                OkeLoadBalancerSpecTest.service(Map.of("service.beta.kubernetes.io/oci-load-balancer-internal", "true"))),
                clock.instant());

        assertTrue(internal.changed());
        assertNull(internal.record().getPublicIp());
        assertEquals("10.0.10.254", internal.record().getPrivateIp());
        assertEquals(1, service.loadBalancerRecords(cluster).size());
    }

    @Test
    void deletingAClusterDropsItsLoadBalancerRecords() {
        OkeService service = loadBalancerService();
        StoredOkeCluster cluster = activeEmulatedCluster("ocid1.cluster.oc1.iad.lbdelete");
        service.reserve(cluster, OkeLoadBalancerSpec.from(OkeLoadBalancerSpecTest.service(Map.of())), clock.instant());

        service.deleteCluster(cluster.getId());

        assertTrue(loadBalancers.keys().isEmpty());
    }

    @Test
    void onlyActiveEmulatedClustersAreReconciled() {
        OkeService service = loadBalancerService();
        StoredOkeCluster legacy = activeEmulatedCluster("ocid1.cluster.oc1.iad.lblegacy");
        legacy.setLoadBalancerMode(null);
        StoredOkeCluster creating = activeEmulatedCluster("ocid1.cluster.oc1.iad.lbcreating");
        creating.setLifecycleState("CREATING");

        service.pollLoadBalancers();

        verify(readinessManager, never()).snapshot(any());
    }

    private StoredOkeCluster storedCluster(String id, String caCertificate) {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId(id);
        cluster.setLifecycleState("ACTIVE");
        cluster.setCaCertificate(caCertificate);
        clusters.put(id, cluster);
        return cluster;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
