package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerDetector;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.oci.core.common.docker.DockerHostResolver;
import io.floci.oci.core.common.docker.PortAllocator;
import io.floci.oci.services.oke.model.StoredNode;
import io.floci.oci.services.oke.model.StoredNodePool;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OkeClusterManagerTest {

    @Mock
    private ContainerLifecycleManager lifecycleManager;

    @Mock
    private PortAllocator portAllocator;

    @Mock
    private ContainerBuilder containerBuilder;

    @Mock
    private DockerHostResolver dockerHostResolver;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private CurrentContainerNetworkResolver networkResolver;

    private EmulatorConfig config;
    private ContainerBuilder.Builder specBuilder;
    private OkeClusterManager manager;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.services().oke().mock()).thenReturn(false);
        lenient().when(config.services().oke().apiServerBasePort()).thenReturn(6443);
        lenient().when(config.services().oke().apiServerMaxPort()).thenReturn(6543);
        lenient().when(config.services().oke().imageRepository()).thenReturn("rancher/k3s");
        lenient().when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(lifecycleManager.containerImage(anyString())).thenReturn("rancher/k3s:v1.30.1-k3s1");
        lenient().when(config.storage().mode()).thenReturn("memory");
        lenient().when(config.storage().pruneVolumesOnDelete()).thenReturn(true);
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.port()).thenReturn(4599);
        lenient().when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");

        specBuilder = mock(ContainerBuilder.Builder.class, RETURNS_DEEP_STUBS);
        lenient().when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withName(anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withEntrypoint(anyList())).thenReturn(specBuilder);
        lenient().when(specBuilder.withCmd(anyList())).thenReturn(specBuilder);
        lenient().when(specBuilder.withEnv(anyString(), anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withPortBinding(anyInt(), anyInt())).thenReturn(specBuilder);
        lenient().when(specBuilder.withNamedVolume(anyString(), anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withPrivileged(org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(specBuilder);
        lenient().when(specBuilder.withLabels(any())).thenReturn(specBuilder);
        lenient().when(specBuilder.withDockerNetwork(any())).thenReturn(specBuilder);
        lenient().when(specBuilder.withPrimaryNetwork(anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withLabel(anyString(), anyString())).thenReturn(specBuilder);
        lenient().when(lifecycleManager.networkSubnet(anyString())).thenReturn(Optional.of("10.0.10.0/24"));
        lenient().when(specBuilder.withHostDockerInternalOnLinux()).thenReturn(specBuilder);
        lenient().when(specBuilder.build()).thenReturn(new ContainerSpec("rancher/k3s:v1.30.1-k3s1"));

        manager = new OkeClusterManager(containerBuilder, lifecycleManager, portAllocator,
                dockerHostResolver, containerDetector, networkResolver, config);
    }

    @Test
    void startClusterFailureReleasesPortAndCleansUpState() {
        when(lifecycleManager.volumeConfirmedAbsent(anyString())).thenReturn(true);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("Docker failure"));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster001");
        cluster.setApiToken("token-testcluster001");
        cluster.setName("failing-cluster");

        assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));

        String expectedContainer = ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
        String expectedVolume = ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());

        verify(lifecycleManager, times(2)).removeIfExists(expectedContainer);
        verify(lifecycleManager).removeVolume(expectedVolume);
        verify(portAllocator).release(6443);
        assertEquals(0, cluster.getHostPort());
        assertEquals("FAILED", cluster.getLifecycleState());
    }

    @Test
    void startClusterFailureRemovesTheVolumeItCreatedWhateverThePrunePolicy() {
        when(lifecycleManager.volumeConfirmedAbsent(anyString())).thenReturn(true);
        lenient().when(config.storage().mode()).thenReturn("file");
        lenient().when(config.storage().pruneVolumesOnDelete()).thenReturn(false);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("Docker failure"));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster004");
        cluster.setApiToken("token-testcluster004");
        cluster.setName("failing-persistent-cluster");

        assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));

        String expectedVolume = ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());
        verify(lifecycleManager).removeVolume(expectedVolume);
    }

    @Test
    void startClusterFailureKeepsAVolumeFromAnEarlierStart() {
        // A restart or a sidecar upgrade that fails must not take the cluster's data and CA with it.
        when(lifecycleManager.volumeConfirmedAbsent(anyString())).thenReturn(false);
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("Docker failure"));
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.keepvolume001");

        assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));

        verify(lifecycleManager, never()).removeVolume(anyString());
        verify(portAllocator).release(6460);
        assertEquals("FAILED", cluster.getLifecycleState());
    }

    @Test
    void startClusterFailureKeepsTheVolumeWhenItCouldNotBeInspected() {
        when(lifecycleManager.volumeConfirmedAbsent(anyString())).thenThrow(new RuntimeException("daemon gone"));
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.keepvolume003");

        assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));

        verify(lifecycleManager, never()).removeVolume(anyString());
    }

    @Test
    void aFailedUpgradeOfARunningSidecarWithoutTheSanKeepsItsVolume() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.keepvolume002");
        String webhookKubeconfig = manager.webhookKubeconfig(cluster);
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig));
        when(lifecycleManager.volumeConfirmedAbsent(anyString())).thenReturn(false);
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("Docker failure"));

        assertThrows(RuntimeException.class, () -> manager.startCluster(cluster));

        verify(lifecycleManager, never()).removeVolume(anyString());
    }

    @Test
    void clusterRenameDoesNotBreakStopCluster() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-123", Map.of()));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster002");
        cluster.setApiToken("token-testcluster002");
        cluster.setName("original-name");

        manager.startCluster(cluster);

        // Rename the display name on the cluster object
        cluster.setName("renamed-display-name");

        manager.stopCluster(cluster);

        String expectedContainer = ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
        String expectedVolume = ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());

        // Called once in startCluster and once in stopCluster
        verify(lifecycleManager, times(2)).removeIfExists(expectedContainer);
        verify(lifecycleManager).removeVolume(expectedVolume);
        verify(portAllocator).release(6443);
    }

    @Test
    void stopClusterFailureDoesNotReleasePort() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster006");
        cluster.setApiToken("token-testcluster006");
        cluster.setName("failing-stop-cluster");

        manager.startCluster(cluster);

        String expectedContainer = ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
        org.mockito.Mockito.doThrow(new RuntimeException("Container removal failure"))
                .when(lifecycleManager).removeIfExists(expectedContainer);

        assertThrows(RuntimeException.class, () -> manager.stopCluster(cluster));

        verify(portAllocator, never()).release(6443);
    }

    @Test
    void clearStopsActiveContainersAndReleasesPorts() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443).thenReturn(6444);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));

        StoredOkeCluster cluster1 = new StoredOkeCluster();
        cluster1.setId("ocid1.cluster.oc1.iad.cluster001");
        cluster1.setApiToken("token-cluster001");
        cluster1.setName("cluster-1");

        StoredOkeCluster cluster2 = new StoredOkeCluster();
        cluster2.setId("ocid1.cluster.oc1.iad.cluster002");
        cluster2.setApiToken("token-cluster002");
        cluster2.setName("cluster-2");

        manager.startCluster(cluster1);
        manager.startCluster(cluster2);

        manager.clear();

        verify(portAllocator).release(6443);
        verify(portAllocator).release(6444);
        verify(lifecycleManager, times(2)).removeIfExists(ContainerStorageHelper.dockerName(config, "oke-" + cluster1.getId()));
        verify(lifecycleManager, times(2)).removeIfExists(ContainerStorageHelper.dockerName(config, "oke-" + cluster2.getId()));
    }

    @Test
    void clearReleasesPortsEvenIfVolumeCleanupThrowsException() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        org.mockito.Mockito.doThrow(new RuntimeException("Volume removal error")).when(lifecycleManager).removeVolume(anyString());

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.cluster005");
        cluster.setApiToken("token-cluster005");
        cluster.setName("cluster-volume-error");

        manager.startCluster(cluster);

        manager.clear();

        verify(portAllocator).release(6443);
    }

    @Test
    void stopClusterRetainsVolumeWhenStorageIsPersistentAndPruneIsFalse() {
        when(config.storage().mode()).thenReturn("file");
        when(config.storage().pruneVolumesOnDelete()).thenReturn(false);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-123", Map.of()));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.testcluster003");
        cluster.setApiToken("token-testcluster003");
        cluster.setName("persistent-cluster");

        manager.startCluster(cluster);
        manager.stopCluster(cluster);

        String expectedContainer = ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
        String expectedVolume = ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());

        verify(lifecycleManager, times(2)).removeIfExists(expectedContainer);
        verify(lifecycleManager, never()).removeVolume(expectedVolume);
        verify(portAllocator).release(6443);
    }

    @Test
    void resetRemovesEveryVolumeEvenWhenStorageIsPersistentAndPruneIsFalse() {
        // Lenient: the point is that reset never consults the policy.
        lenient().when(config.storage().mode()).thenReturn("file");
        lenient().when(config.storage().pruneVolumesOnDelete()).thenReturn(false);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.cluster007");
        cluster.setApiToken("token-cluster007");
        cluster.setName("reset-cluster");
        manager.startCluster(cluster);

        manager.clear();

        verify(lifecycleManager).removeVolume(ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId()));
        verify(portAllocator).release(6443);
    }

    @Test
    void shutdownKeepsVolumesWhenStorageIsPersistentAndPruneIsFalse() {
        when(config.storage().mode()).thenReturn("file");
        when(config.storage().pruneVolumesOnDelete()).thenReturn(false);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.cluster008");
        cluster.setApiToken("token-cluster008");
        cluster.setName("persistent-across-restart");
        manager.startCluster(cluster);

        manager.shutdown();

        // The k3s data, and with it the cluster's CA, must survive a restart of floci-oci.
        verify(lifecycleManager, never()).removeVolume(anyString());
        verify(lifecycleManager, times(2)).removeIfExists(ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId()));
        verify(portAllocator).release(6443);
    }

    @Test
    void startClusterCreatesTheLabelledVolumeBeforeTheContainer() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.cluster009");
        cluster.setApiToken("token-cluster009");
        cluster.setName("labelled-volume");

        manager.startCluster(cluster);

        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).ensureVolume(ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId()));
        order.verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void registerExistingClusterRestartsContainerAndReservesPort() {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.reconstructed001");
        cluster.setApiToken("token-reconstructed001");
        cluster.setHostPort(6445);
        cluster.setName("reconstructed-cluster");

        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(false);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-999", Map.of()));

        manager.registerExistingCluster(cluster);

        verify(portAllocator).markReserved(6445);
        String expectedContainer = ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
        verify(lifecycleManager).removeIfExists(expectedContainer);
        verify(lifecycleManager).createAndStart(any());
        assertEquals("https://127.0.0.1:6445", cluster.getEndpoints().get("kubernetes"));
    }

    @Test
    void startClusterRunsK3sServerWithTheClusterApiToken() {
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-789", Map.of()));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.servermode001");
        cluster.setApiToken("token-servermode001");

        cluster.setTenancyId("ocid1.tenancy.oc1..servermode");

        manager.startCluster(cluster);

        verify(specBuilder).withEntrypoint(OkeClusterManager.K3S_ENTRYPOINT);
        verify(specBuilder).withCmd(List.of("k3s", "server", "--disable-agent", "--egress-selector-mode=cluster",
                "--cluster-cidr=10.244.0.0/16", "--service-cidr=10.96.0.0/16", "--cluster-dns=10.96.5.5",
                "--kube-controller-manager-arg=node-cidr-mask-size=25",
                "--disable=traefik", "--disable=local-storage", "--disable=metrics-server",
                "--kube-apiserver-arg=token-auth-file=" + OkeClusterManager.TOKEN_FILE,
                "--kube-apiserver-arg=authentication-token-webhook-config-file=" + OkeClusterManager.WEBHOOK_FILE,
                "--kube-apiserver-arg=authentication-token-webhook-version=v1",
                "--kube-apiserver-arg=authentication-token-webhook-cache-ttl=30s"));
        verify(specBuilder).withEnv(OkeClusterManager.API_TOKEN_ENV, "token-servermode001");
        verify(specBuilder).withEnv(OkeClusterManager.MANIFESTS_ENV, OkeManifests.clusterManifests());
        verify(specBuilder).withEnv(OkeClusterManager.WEBHOOK_KUBECONFIG_ENV, manager.webhookKubeconfig(cluster));
        verify(specBuilder).withEnv(OkeClusterManager.TLS_SAN_ENV,
                ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId()));
        verify(specBuilder).withPrimaryNetwork(ContainerStorageHelper.dockerName(config, "oke-net-" + cluster.getId()));
        verify(specBuilder).withDockerNetwork(Optional.empty());
        verify(specBuilder).withHostDockerInternalOnLinux();
        assertNull(cluster.getLifecycleState(), "real mode leaves ACTIVE to the readiness poller");
    }

    private StoredNodePool agentPool(String shape) {
        StoredNodePool pool = new StoredNodePool();
        pool.setId("ocid1.nodepool.oc1.iad.agentpool");
        pool.setName("agents");
        pool.setCompartmentId("ocid1.compartment.oc1..agents");
        pool.setNodeShape(shape);
        return pool;
    }

    private StoredNode agentNode() {
        StoredNode node = new StoredNode();
        node.setId("ocid1.instance.oc1.iad.agentnode");
        node.setName("oke-cagent-nagent-sagent-0");
        node.setPrivateIp("10.0.10.16");
        node.setAvailabilityDomain("Floc:US-ASHBURN-AD-1");
        node.setFaultDomain("FAULT-DOMAIN-1");
        node.setKubernetesVersion("v1.35.2");
        return node;
    }

    @SuppressWarnings("unchecked")
    private List<String> agentArgs(String shape) {
        lenient().when(specBuilder.withIpv4Address(anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withNamedVolume(anyString(), anyString())).thenReturn(specBuilder);
        lenient().when(specBuilder.withLogRotation()).thenReturn(specBuilder);
        lenient().when(specBuilder.withMemoryBytes(org.mockito.ArgumentMatchers.anyLong())).thenReturn(specBuilder);
        lenient().when(specBuilder.withNanoCpus(org.mockito.ArgumentMatchers.anyLong())).thenReturn(specBuilder);
        lenient().when(lifecycleManager.exec(anyString(), any(), anyList(), any()))
                .thenReturn(new ContainerLifecycleManager.ExecResult(0, "", ""));
        lenient().when(lifecycleManager.hostResources())
                .thenReturn(Optional.of(new ContainerLifecycleManager.HostResources(6, 10L << 30)));
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.agents001");
        cluster.setJoinToken("K10hash::server:secret");

        assertTrue(manager.ensureNode(cluster, agentPool(shape), agentNode()));

        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        verify(specBuilder).withCmd(cmd.capture());
        verify(specBuilder).withEnv("K3S_TOKEN", "K10hash::server:secret");
        verify(specBuilder).withEnv("K3S_URL", "https://" + containerName(cluster) + ":6443");
        verify(specBuilder).withPrimaryNetwork(ContainerStorageHelper.dockerName(config, "oke-net-" + cluster.getId()));
        verify(specBuilder).withIpv4Address("10.0.10.16");
        return cmd.getValue();
    }

    @Test
    void anAgentRegistersUnderItsAddressWithAnOciProviderIdAndOkeLabels() {
        List<String> args = agentArgs("VM.Standard.E4.Flex");

        assertEquals("agent", args.getFirst());
        assertTrue(args.contains("--node-name=10.0.10.16"));
        assertTrue(args.contains("--kubelet-arg=provider-id=oci://ocid1.instance.oc1.iad.agentnode"));
        assertTrue(args.contains("--node-label=node.kubernetes.io/instance-type=VM.Standard.E4.Flex"));
        assertTrue(args.contains("--node-label=topology.kubernetes.io/zone=US-ASHBURN-AD-1"));
        assertTrue(args.contains("--kubelet-arg=system-reserved=cpu=4000m"), args.toString());
        assertFalse(args.stream().anyMatch(arg -> arg.startsWith("--node-taint")));
        verify(specBuilder).withNanoCpus(2_000_000_000L);
    }

    @Test
    void removingANodeStopsItsAgentBeforeDeletingItsKubernetesNode() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.removal001");
        when(lifecycleManager.isContainerRunning(containerName(cluster))).thenReturn(true);
        when(lifecycleManager.exec(anyString(), any(), anyList(), any()))
                .thenReturn(new ContainerLifecycleManager.ExecResult(0, "", ""));
        StoredNode node = agentNode();

        manager.removeNode(cluster, node, true);

        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).removeIfExists(manager.nodeContainerName(node));
        order.verify(lifecycleManager).exec(eq(containerName(cluster)), any(),
                eq(List.of("kubectl", "delete", "node", "10.0.10.16", "--ignore-not-found", "--wait=false")), any());
        verify(lifecycleManager).removeVolume(manager.nodeVolumeName(node));
    }

    @Test
    void aNodeIsFinishedOnlyOnceItsKubeletRunsTheNodesVersion() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.finish001");
        StoredNode node = agentNode();
        List<String> getVersion = List.of("kubectl", "get", "node", "10.0.10.16", "-o",
                "jsonpath={.status.nodeInfo.kubeletVersion}");
        lenient().when(lifecycleManager.exec(anyString(), any(), anyList(), any()))
                .thenReturn(new ContainerLifecycleManager.ExecResult(0, "", ""));
        when(lifecycleManager.exec(eq(containerName(cluster)), any(), eq(getVersion), any()))
                .thenReturn(new ContainerLifecycleManager.ExecResult(0, "v1.34.10+k3s1", ""))
                .thenReturn(new ContainerLifecycleManager.ExecResult(0, "v1.35.2+k3s1", ""));

        assertFalse(manager.finishNode(cluster, agentPool("VM.Standard.E4.Flex"), node),
                "the Node object still belongs to the kubelet the node ran before");
        verify(lifecycleManager, never()).exec(anyString(), any(),
                org.mockito.ArgumentMatchers.argThat(cmd -> cmd.contains("label")), any());
        assertTrue(manager.finishNode(cluster, agentPool("VM.Standard.E4.Flex"), node));
    }

    @Test
    void agentsOfNodesNoLongerInTheClusterAreRemovedWithTheirVolumes() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.orphans001");
        com.github.dockerjava.api.model.Container kept = mock(com.github.dockerjava.api.model.Container.class);
        lenient().when(kept.getId()).thenReturn("kept-id");
        when(kept.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, "ocid1.instance.oc1.iad.kept",
                OkeClusterManager.NODE_VOLUME_LABEL, "kept-volume"));
        com.github.dockerjava.api.model.Container orphan = mock(com.github.dockerjava.api.model.Container.class);
        when(orphan.getId()).thenReturn("orphan-id");
        when(orphan.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL,
                "ocid1.instance.oc1.iad.gone", OkeClusterManager.NODE_VOLUME_LABEL, "orphan-volume"));
        com.github.dockerjava.api.model.Container serverContainer = mock(com.github.dockerjava.api.model.Container.class);
        when(serverContainer.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, cluster.getId()));
        when(lifecycleManager.listByLabel(OkeClusterManager.CLUSTER_LABEL, cluster.getId()))
                .thenReturn(List.of(kept, orphan, serverContainer));

        manager.removeOrphanAgents(cluster, java.util.Set.of("ocid1.instance.oc1.iad.kept"));

        verify(lifecycleManager).removeIfExists("orphan-id");
        verify(lifecycleManager).removeVolume("orphan-volume");
        verify(lifecycleManager, never()).removeIfExists("kept-id");
        verify(lifecycleManager, never()).removeVolume("kept-volume");
        String server = containerName(cluster);
        verify(lifecycleManager, never()).removeIfExists(server);
    }

    @Test
    void aGpuShapeAgentCarriesOkesGpuTaint() {
        List<String> args = agentArgs("VM.GPU.A10.1");

        assertTrue(args.contains("--node-taint=nvidia.com/gpu=present:NoSchedule"));
    }

    @Test
    void k3sEntrypointWritesTokenAndWebhookFilesFromTheEnvironment() {
        String script = OkeClusterManager.K3S_ENTRYPOINT.get(2);
        assertEquals(List.of("sh", "-c"), OkeClusterManager.K3S_ENTRYPOINT.subList(0, 2));
        assertTrue(script.contains("\"$" + OkeClusterManager.API_TOKEN_ENV + "\""));
        assertTrue(script.contains("\"$" + OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "\" > "
                + OkeClusterManager.WEBHOOK_FILE));
        assertTrue(script.contains("system:masters"));
        assertTrue(script.contains("\"$" + OkeClusterManager.MANIFESTS_ENV + "\" > " + OkeClusterManager.MANIFEST_FILE));
        assertTrue(script.endsWith("exec /bin/k3s \"$@\" --tls-san=\"$" + OkeClusterManager.TLS_SAN_ENV + "\""));
    }

    @Test
    void webhookKubeconfigScopesTheUrlByTenancyAndClusterInThePath() {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.webhook001");
        cluster.setTenancyId("ocid1.tenancy.oc1..webhooktenancy");

        String kubeconfig = manager.webhookKubeconfig(cluster);

        assertTrue(kubeconfig.contains("server: http://host.docker.internal:4599"
                + "/_floci-oci/oke/token-webhook/ocid1.tenancy.oc1..webhooktenancy/ocid1.cluster.oc1.iad.webhook001\n"),
                kubeconfig);
        assertFalse(kubeconfig.contains("?"), "client-go drops the query, so the scope must be in the path");
        assertFalse(kubeconfig.contains("insecure-skip-tls-verify"));
    }

    @Test
    void webhookStaysPlainHttpWhenFlociServesTls() {
        lenient().when(config.tls().enabled()).thenReturn(true);
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.webhook002");
        cluster.setTenancyId("ocid1.tenancy.oc1..webhooktenancy");

        String kubeconfig = manager.webhookKubeconfig(cluster);

        assertTrue(kubeconfig.contains("server: http://host.docker.internal:4599/"), kubeconfig);
        assertFalse(kubeconfig.contains("insecure-skip-tls-verify"), kubeconfig);
    }

    @Test
    void apiServerUrlIsThePublishedPortWhenFlociRunsOnTheHost() throws Exception {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.3startswithadigit");
        cluster.setHostPort(6450);
        when(containerDetector.isRunningInContainer()).thenReturn(false);

        assertEquals("https://127.0.0.1:6450", manager.apiServerUrl(cluster));
    }

    @Test
    void apiServerUrlIsTheSidecarIpOnFlocisOwnNetworkWhenFlociRunsInDocker() throws Exception {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.3startswithadigit");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("compat-net"));
        when(lifecycleManager.resolveEndpoint(anyString(), eq(6443), eq("compat-net")))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("172.18.0.5", 6443));

        assertEquals("https://172.18.0.5:6443", manager.apiServerUrl(cluster));
    }

    @Test
    void apiServerUrlPrefersTheConfiguredDockerNetworkTheSidecarJoined() throws Exception {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.confignet001");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.services().dockerNetwork()).thenReturn(Optional.of("shared-net"));
        lenient().when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("floci-net"));
        when(lifecycleManager.resolveEndpoint(anyString(), eq(6443), eq("shared-net")))
                .thenReturn(new ContainerLifecycleManager.EndpointInfo("172.19.0.7", 6443));

        assertEquals("https://172.19.0.7:6443", manager.apiServerUrl(cluster));
    }

    @Test
    void apiServerUrlReportsAVanishedSidecarAsAnIoFailure() {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.vanished001");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.empty());
        when(lifecycleManager.resolveEndpoint(anyString(), eq(6443), isNull())).thenThrow(new IllegalStateException("gone"));

        assertThrows(IOException.class, () -> manager.apiServerUrl(cluster));
    }

    @Test
    void startClusterAdoptsARunningSidecarCreatedForThisCluster() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.adopt001");
        String webhookKubeconfig = manager.webhookKubeconfig(cluster);
        String tlsSan = containerName(cluster);
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig,
                OkeClusterManager.TLS_SAN_ENV + "=" + tlsSan,
                OkeClusterManager.SERVER_ARGS_ENV + "=" + String.join(" ", OkeClusterManager.buildServerArgs(cluster)),
                OkeClusterManager.MANIFESTS_ENV + "=" + OkeManifests.clusterManifests()));
        when(lifecycleManager.containerLabels(anyString())).thenReturn(
                Map.of(ContainerStorageHelper.REGION_LABEL, "us-ashburn-1"));

        manager.startCluster(cluster);

        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void startClusterRecreatesARunningSidecarOnAnotherVersionsImage() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.adopt004");
        cluster.setKubernetesVersion("v1.36.4");
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerImage(anyString())).thenReturn("rancher/k3s:v1.35.2-k3s1");
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-new", Map.of()));

        manager.startCluster(cluster);

        verify(containerBuilder).newContainer("rancher/k3s:v1.36.4-k3s1");
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void startClusterRecreatesARunningSidecarLabelledWithAnotherRegion() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.phx.adopt003");
        cluster.setRegion("us-phoenix-1");
        String webhookKubeconfig = manager.webhookKubeconfig(cluster);
        String tlsSan = containerName(cluster);
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig,
                OkeClusterManager.TLS_SAN_ENV + "=" + tlsSan,
                OkeClusterManager.SERVER_ARGS_ENV + "=" + String.join(" ", OkeClusterManager.buildServerArgs(cluster)),
                OkeClusterManager.MANIFESTS_ENV + "=" + OkeManifests.clusterManifests()));
        when(lifecycleManager.containerLabels(anyString())).thenReturn(
                Map.of(ContainerStorageHelper.REGION_LABEL, "us-ashburn-1"));
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-new", Map.of()));

        manager.startCluster(cluster);

        verify(lifecycleManager).removeIfExists(anyString());
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void startClusterRecreatesARunningSidecarWithoutTheCurrentWebhook() {
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.adopt002");
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken()));
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-new", Map.of()));

        manager.startCluster(cluster);

        verify(lifecycleManager).removeIfExists(anyString());
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void startClusterRecreatesARunningSidecarWithoutTheContainerNameSan() {
        // Created by a floci-oci that did not add the SAN: its certificate cannot serve network mode.
        StoredOkeCluster cluster = adoptableCluster("ocid1.cluster.oc1.iad.adopt004");
        String webhookKubeconfig = manager.webhookKubeconfig(cluster);
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(true);
        when(lifecycleManager.containerEnv(anyString())).thenReturn(List.of(
                OkeClusterManager.API_TOKEN_ENV + "=" + cluster.getApiToken(),
                OkeClusterManager.WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig));
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-new", Map.of()));

        manager.startCluster(cluster);

        verify(lifecycleManager).removeIfExists(anyString());
        verify(lifecycleManager).createAndStart(any());
    }

    @Test
    void networkModeAdvertisesTheSidecarContainerNameWhenFlociRunsInDocker() {
        when(config.services().oke().endpointMode()).thenReturn("network");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        lenient().when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("floci_oci_default"));
        StoredOkeCluster cluster = startedCluster("ocid1.cluster.oc1.iad.netmode001", 6470);

        assertEquals("https://" + containerName(cluster) + ":6443", cluster.getEndpoints().get("kubernetes"));
        assertEquals("10.0.0.10:6470", cluster.getEndpoints().get("privateEndpoint"));
    }

    @Test
    void networkModeFallsBackToTheHostPortOnANetworkWithoutNameLookup() {
        when(config.services().oke().endpointMode()).thenReturn("network");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        lenient().when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("bridge"));
        StoredOkeCluster cluster = startedCluster("ocid1.cluster.oc1.iad.netmode003", 6473);

        assertEquals("https://127.0.0.1:6473", cluster.getEndpoints().get("kubernetes"));
    }

    @Test
    void networkModeUsesTheConfiguredDockerNetworkWhenItHasNameLookup() {
        when(config.services().oke().endpointMode()).thenReturn("network");
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        when(config.services().dockerNetwork()).thenReturn(Optional.of("shared-net"));
        lenient().when(networkResolver.resolveNetworkName()).thenReturn(Optional.of("bridge"));
        StoredOkeCluster cluster = startedCluster("ocid1.cluster.oc1.iad.netmode004", 6474);

        assertEquals("https://" + containerName(cluster) + ":6443", cluster.getEndpoints().get("kubernetes"));
    }

    @Test
    void networkModeFallsBackToTheHostPortWhenFlociRunsOnTheHost() {
        lenient().when(config.services().oke().endpointMode()).thenReturn("NETWORK");
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        StoredOkeCluster cluster = startedCluster("ocid1.cluster.oc1.iad.netmode002", 6471);

        assertEquals("https://127.0.0.1:6471", cluster.getEndpoints().get("kubernetes"));
    }

    @Test
    void hostModeAdvertisesTheHostPortEvenWhenFlociRunsInDocker() {
        when(config.services().oke().endpointMode()).thenReturn("host");
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(true);
        StoredOkeCluster cluster = startedCluster("ocid1.cluster.oc1.iad.hostmode001", 6472);

        assertEquals("https://127.0.0.1:6472", cluster.getEndpoints().get("kubernetes"));
    }

    @Test
    void endpointModeAcceptsHostAndNetworkInAnyCaseAndRejectsTheRest() {
        OkeClusterManager.validateEndpointMode("host");
        OkeClusterManager.validateEndpointMode("Network");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> OkeClusterManager.validateEndpointMode("public"));
        assertTrue(e.getMessage().contains("floci-oci.services.oke.endpoint-mode"), e.getMessage());
    }

    private StoredOkeCluster startedCluster(String id, int hostPort) {
        StoredOkeCluster cluster = adoptableCluster(id);
        cluster.setHostPort(hostPort);
        when(lifecycleManager.isContainerRunning(anyString())).thenReturn(false);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-" + hostPort, Map.of()));
        manager.startCluster(cluster);
        return cluster;
    }

    private String containerName(StoredOkeCluster cluster) {
        return ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
    }

    private StoredOkeCluster adoptableCluster(String id) {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId(id);
        cluster.setApiToken("token-" + id);
        cluster.setTenancyId("ocid1.tenancy.oc1..adopt");
        cluster.setHostPort(6460);
        return cluster;
    }

    @Test
    void containerNamesWithADigitLeadingLabelAreNotValidUriHosts() {
        // Why apiServerUrl dials the sidecar's IP in Docker instead of its container name.
        String name = ContainerStorageHelper.dockerName(config, "oke-ocid1.cluster.oc1.iad.3gij5xynypbmq");

        assertNull(URI.create("https://" + name + ":6443").getHost());
    }

    @Test
    void startClusterWithoutApiTokenFailsBeforeTouchingDocker() {
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.notoken001");

        assertThrows(IllegalStateException.class, () -> manager.startCluster(cluster));

        verify(portAllocator, never()).allocate(anyInt(), anyInt());
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void startClusterLabelsContainerWithClusterIdentity() {
        OkeClusterManager labelledManager = new OkeClusterManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class), null), lifecycleManager, portAllocator,
                dockerHostResolver, containerDetector, networkResolver, config);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));

        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.labelled001");
        cluster.setCompartmentId("ocid1.compartment.oc1..labelled");
        cluster.setName("labelled-cluster");
        cluster.setApiToken("token-labelled001");

        labelledManager.startCluster(cluster);

        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(spec.capture());
        assertEquals(
                Map.of(
                        "io.floci", "oci",
                        "io.floci.service", "oke",
                        "io.floci.resource-id", "ocid1.cluster.oc1.iad.labelled001",
                        "io.floci.compartment", "ocid1.compartment.oc1..labelled",
                        "io.floci.region", "us-ashburn-1",
                        OkeClusterManager.CLUSTER_LABEL, "ocid1.cluster.oc1.iad.labelled001"),
                spec.getValue().labels());
        assertEquals(ContainerStorageHelper.dockerName(config, "oke-net-" + cluster.getId()),
                spec.getValue().primaryNetwork(), "k3s must start on the cluster network to advertise an address on it");
    }

    @Test
    void startClusterLabelsContainerWithTheClusterRegion() {
        OkeClusterManager labelledManager = new OkeClusterManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class), null), lifecycleManager, portAllocator,
                dockerHostResolver, containerDetector, networkResolver, config);
        when(portAllocator.allocate(6443, 6543)).thenReturn(6443);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("c-1", Map.of()));
        StoredOkeCluster cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.phx.labelled002");
        cluster.setCompartmentId("ocid1.compartment.oc1..labelled");
        cluster.setName("phoenix-cluster");
        cluster.setApiToken("token-labelled002");
        cluster.setRegion("us-phoenix-1");

        labelledManager.startCluster(cluster);

        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).createAndStart(spec.capture());
        assertEquals("us-phoenix-1", spec.getValue().labels().get("io.floci.region"));
    }
}
