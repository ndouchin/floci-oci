package io.floci.oci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.InternetProtocol;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.Ports;
import io.floci.oci.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * What a load balancer appliance needs from Docker: {@code NET_ADMIN}, IP forwarding, UDP and
 * loopback-only published ports, and more networks joined at fixed addresses once it runs.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerApplianceTest {

    @Mock
    private DockerClient dockerClient;

    @Mock
    private ImageCacheService imageCacheService;

    @Mock
    private ContainerDetector containerDetector;

    @Mock
    private EmulatorConfig config;

    @Mock
    private EmulatorConfig.DockerConfig dockerConfig;

    private ContainerLifecycleManager manager;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(dockerConfig.imageRegistryBase()).thenReturn(Optional.empty());
        manager = new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, config);
    }

    @Test
    void capabilitiesSysctlsAndLoopbackUdpPortsReachDocker() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd("rancher/k3s:v1.35.2-k3s1")).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("appliance");
        when(createCmd.exec()).thenReturn(response);

        manager.create(builder().newContainer("rancher/k3s:v1.35.2-k3s1")
                .withCapAdd("NET_ADMIN")
                .withSysctl("net.ipv4.ip_forward", "1")
                .withPublishedPort(53, "udp", "127.0.0.1", 0)
                .withPublishedPort(8080, "tcp", "127.0.0.1", 41234)
                .build());

        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        assertArrayEquals(new Capability[] {Capability.NET_ADMIN}, hostConfig.getValue().getCapAdd());
        assertEquals(Map.of("net.ipv4.ip_forward", "1"), hostConfig.getValue().getSysctls());
        Map<ExposedPort, Ports.Binding[]> bindings = hostConfig.getValue().getPortBindings().getBindings();
        Ports.Binding udp = bindings.get(new ExposedPort(53, InternetProtocol.UDP))[0];
        assertEquals("127.0.0.1", udp.getHostIp());
        assertTrue(udp.getHostPortSpec() == null || udp.getHostPortSpec().isEmpty());
        Ports.Binding tcp = bindings.get(new ExposedPort(8080, InternetProtocol.TCP))[0];
        assertEquals("127.0.0.1", tcp.getHostIp());
        assertEquals("41234", tcp.getHostPortSpec());
        ArgumentCaptor<ExposedPort[]> exposed = ArgumentCaptor.forClass(ExposedPort[].class);
        verify(createCmd).withExposedPorts(exposed.capture());
        assertEquals(List.of(new ExposedPort(53, InternetProtocol.UDP), new ExposedPort(8080, InternetProtocol.TCP)),
                List.of(exposed.getValue()));
    }

    @Test
    void networkAttachmentsJoinAfterTheStartAtTheirFixedAddress() {
        when(dockerClient.startContainerCmd("appliance")).thenReturn(mock(StartContainerCmd.class));
        ConnectToNetworkCmd connect = mock(ConnectToNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.connectToNetworkCmd()).thenReturn(connect);

        manager.startCreated("appliance", builder().newContainer("rancher/k3s:v1.35.2-k3s1")
                .withNetworkAttachment("cluster-net", "10.0.10.200")
                .withNetworkAttachment("floci-net", null)
                .build());

        verify(connect).withNetworkId("cluster-net");
        verify(connect).withNetworkId("floci-net");
        ArgumentCaptor<ContainerNetwork> network = ArgumentCaptor.forClass(ContainerNetwork.class);
        verify(connect, times(2)).withContainerNetwork(network.capture());
        assertEquals("10.0.10.200", network.getAllValues().get(0).getIpamConfig().getIpv4Address());
        assertNull(network.getAllValues().get(1).getIpamConfig());
        verify(connect, times(2)).exec();
    }

    @Test
    void publishedHostPortsAreReadBackByProtocolAndPort() {
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        NetworkSettings settings = mock(NetworkSettings.class);
        Ports ports = new Ports();
        ports.bind(new ExposedPort(53, InternetProtocol.UDP), Ports.Binding.bindIpAndPort("127.0.0.1", 41235));
        ports.bind(new ExposedPort(8080, InternetProtocol.TCP), Ports.Binding.bindIpAndPort("127.0.0.1", 41234));
        when(dockerClient.inspectContainerCmd("appliance")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspect);
        when(inspect.getNetworkSettings()).thenReturn(settings);
        when(settings.getPorts()).thenReturn(ports);

        assertEquals(Map.of("UDP/53", 41235, "TCP/8080", 41234), manager.publishedHostPorts("appliance"));
    }

    private ContainerBuilder builder() {
        return new ContainerBuilder(config, mock(DockerHostResolver.class), null);
    }
}
