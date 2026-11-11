package io.floci.oci.services.oke;

import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.DockerHostResolver;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The appliance is the cluster's own k3s image running {@code sh -c} with iptables rules that DNAT
 * each Service port to the backends' NodePorts (spread at random) and masquerade, then sleep, with
 * {@code NET_ADMIN} and IP forwarding, not privileged (the map's spike: 0.9 s to start, 400 KiB).
 */
class OkeLoadBalancerApplianceManagerTest {

    private static final OkeLoadBalancerSpec.Port HTTP = new OkeLoadBalancerSpec.Port("http", "TCP", 80, 30080);
    private static final OkeLoadBalancerSpec.Port DNS = new OkeLoadBalancerSpec.Port("dns", "UDP", 53, 30053);

    private EmulatorConfig config;
    private OkeClusterManager clusterManager;
    private OkeLoadBalancerApplianceManager appliances;
    private StoredOkeCluster cluster;

    @BeforeEach
    void setUp() {
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        lenient().when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(config.docker().imageRegistryBase()).thenReturn(Optional.empty());
        lenient().when(config.docker().logMaxSize()).thenReturn("10m");
        lenient().when(config.docker().logMaxFile()).thenReturn("3");
        lenient().when(config.defaultRegion()).thenReturn("us-ashburn-1");
        lenient().when(config.services().oke().loadBalancer().publicCidr()).thenReturn("192.0.2.0/24");
        lenient().when(config.services().oke().loadBalancer().hostPorts()).thenReturn("dynamic");
        clusterManager = mock(OkeClusterManager.class);
        lenient().when(clusterManager.networkName(any())).thenReturn("floci-oci-oke-net-cluster");
        lenient().when(clusterManager.serverImage(any())).thenReturn("rancher/k3s:v1.35.2-k3s1");
        lenient().when(clusterManager.emulatorNetwork()).thenReturn(Optional.empty());
        appliances = new OkeLoadBalancerApplianceManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class), null),
                mock(ContainerLifecycleManager.class), clusterManager, config);
        cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.appliance");
        cluster.setCompartmentId("ocid1.compartment.oc1..appliance");
        cluster.setRegion("us-ashburn-1");
    }

    @Test
    void theScriptSpreadsEachPortOverTheBackendsAndMasquerades() {
        assertEquals("""
                set -e
                IPT=/bin/aux/iptables
                $IPT -t nat -A PREROUTING -p tcp --dport 80 -m statistic --mode random --probability 0.5000000000 -j DNAT --to-destination 10.0.10.16:30080
                $IPT -t nat -A PREROUTING -p tcp --dport 80 -j DNAT --to-destination 10.0.10.17:30080
                $IPT -t nat -A POSTROUTING -p tcp -d 10.0.10.16 --dport 30080 -j MASQUERADE
                $IPT -t nat -A POSTROUTING -p tcp -d 10.0.10.17 --dport 30080 -j MASQUERADE
                exec sleep infinity
                """, OkeLoadBalancerApplianceManager.script(spec(false, "10.0.10.254", null, List.of(HTTP),
                List.of("10.0.10.16", "10.0.10.17"), List.of())));
    }

    @Test
    void sourceRangesLimitWhoIsForwardedAndUdpIsForwardedToo() {
        String script = OkeLoadBalancerApplianceManager.script(spec(false, "10.0.10.254", null, List.of(DNS),
                List.of("10.0.10.16"), List.of("203.0.113.0/24", "198.51.100.0/24")));

        assertTrue(script.contains("$IPT -t nat -A PREROUTING -p udp -s 203.0.113.0/24 --dport 53 -j DNAT"
                + " --to-destination 10.0.10.16:30053\n"), script);
        assertTrue(script.contains("$IPT -t nat -A PREROUTING -p udp -s 198.51.100.0/24 --dport 53 -j DNAT"
                + " --to-destination 10.0.10.16:30053\n"), script);
        assertTrue(script.contains("$IPT -t nat -A POSTROUTING -p udp -d 10.0.10.16 --dport 30053 -j MASQUERADE\n"));
    }

    @Test
    void withoutBackendsTheApplianceForwardsNothing() {
        assertEquals("set -e\nIPT=/bin/aux/iptables\nexec sleep infinity\n",
                OkeLoadBalancerApplianceManager.script(spec(false, "10.0.10.254", null, List.of(HTTP), List.of(),
                        List.of())));
    }

    @Test
    void anInternalApplianceHoldsItsAddressOnTheClusterNetwork() {
        ContainerSpec container = appliances.containerSpec(cluster,
                spec(true, "10.0.10.254", null, List.of(HTTP), List.of("10.0.10.16"), List.of()), Map.of(), true);

        assertEquals("rancher/k3s:v1.35.2-k3s1", container.image());
        assertEquals("floci-oci-oke-lb-uid-1", container.name());
        assertEquals(List.of("sh", "-c"), container.entrypoint().subList(0, 2));
        assertEquals("floci-oci-oke-net-cluster", container.primaryNetwork());
        assertEquals("10.0.10.254", container.ipv4Address());
        assertEquals(List.of("NET_ADMIN"), container.capAdd());
        assertEquals(Map.of("net.ipv4.ip_forward", "1"), container.sysctls());
        assertTrue(!container.privileged());
        assertEquals(List.of(new ContainerSpec.PublishedPort(80, "tcp", "127.0.0.1", 0)), container.publishedPorts());
        assertEquals(List.of(), container.networkAttachments());
        assertEquals("ocid1.cluster.oc1.iad.appliance", container.labels().get(OkeClusterManager.CLUSTER_LABEL));
        assertEquals("uid-1", container.labels().get(OkeLoadBalancerApplianceManager.SERVICE_LABEL));
        assertEquals("false", container.labels().get(OkeLoadBalancerApplianceManager.PUBLIC_LABEL));
    }

    @Test
    void aPublicNetworkLoadBalancerHoldsItsPublicAddressAndJoinsTheClusterAtItsPrivateOne() {
        when(clusterManager.emulatorNetwork()).thenReturn(Optional.of("floci-net"));

        ContainerSpec container = appliances.containerSpec(cluster,
                spec(false, "10.0.10.254", "192.0.2.10", List.of(HTTP), List.of("10.0.10.16"), List.of()),
                Map.of("TCP/80", 41234), true);

        assertEquals(OkeLoadBalancerApplianceManager.publicNetworkName(config), container.primaryNetwork());
        assertEquals("192.0.2.10", container.ipv4Address());
        assertEquals(List.of(new ContainerSpec.NetworkAttachment("floci-oci-oke-net-cluster", "10.0.10.254"),
                new ContainerSpec.NetworkAttachment("floci-net", null)), container.networkAttachments());
        assertEquals(List.of(new ContainerSpec.PublishedPort(80, "tcp", "127.0.0.1", 41234)),
                container.publishedPorts(), "a recreated appliance asks for the host ports it had");
        assertEquals("true", container.labels().get(OkeLoadBalancerApplianceManager.PUBLIC_LABEL));
    }

    @Test
    void aPublicLoadBalancerJoinsTheClusterNetworkAtADynamicAddress() {
        ContainerSpec container = appliances.containerSpec(cluster,
                spec(false, null, "192.0.2.10", List.of(HTTP), List.of("10.0.10.16"), List.of()), Map.of(), true);

        assertEquals(List.of(new ContainerSpec.NetworkAttachment("floci-oci-oke-net-cluster", null)),
                container.networkAttachments());
    }

    @Test
    void hostPortsFollowTheConfiguredPolicy() {
        OkeLoadBalancerAppliances.Spec spec = spec(true, "10.0.10.254", null, List.of(HTTP), List.of(), List.of());

        when(config.services().oke().loadBalancer().hostPorts()).thenReturn("none");
        assertEquals(List.of(), appliances.containerSpec(cluster, spec, Map.of(), true).publishedPorts());
        when(config.services().oke().loadBalancer().hostPorts()).thenReturn("same-as-service");
        assertEquals(List.of(new ContainerSpec.PublishedPort(80, "tcp", "127.0.0.1", 80)),
                appliances.containerSpec(cluster, spec, Map.of(), true).publishedPorts());
        assertEquals(List.of(new ContainerSpec.PublishedPort(80, "tcp", "127.0.0.1", 0)),
                appliances.containerSpec(cluster, spec, Map.of(), false).publishedPorts(),
                "the fallback when the wished-for ports are taken");
    }

    @Test
    void theSpecLabelChangesWithWhatTheApplianceDoesButNotWithTheHostPortsItWishesFor() {
        OkeLoadBalancerAppliances.Spec spec = spec(true, "10.0.10.254", null, List.of(HTTP), List.of("10.0.10.16"),
                List.of());
        String label = appliances.containerSpec(cluster, spec, Map.of(), true).labels()
                .get(OkeLoadBalancerApplianceManager.SPEC_LABEL);

        assertEquals(label, appliances.containerSpec(cluster, spec, Map.of("TCP/80", 41234), true).labels()
                .get(OkeLoadBalancerApplianceManager.SPEC_LABEL));
        assertNotEquals(label, appliances.containerSpec(cluster, spec(true, "10.0.10.254", null, List.of(HTTP),
                List.of("10.0.10.17"), List.of()), Map.of(), true).labels().get(OkeLoadBalancerApplianceManager.SPEC_LABEL));
    }

    private static OkeLoadBalancerAppliances.Spec spec(boolean internal, String privateIp, String publicIp,
                                                       List<OkeLoadBalancerSpec.Port> ports, List<String> backends,
                                                       List<String> sourceRanges) {
        return new OkeLoadBalancerAppliances.Spec("floci-oci-oke-lb-uid-1", "uid-1", internal, privateIp, publicIp,
                ports, backends, sourceRanges, Map.of());
    }
}
