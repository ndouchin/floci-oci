package io.floci.oci.services.oke;

import com.github.dockerjava.api.model.Container;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerBuilder;
import io.floci.oci.core.common.docker.ContainerLifecycleManager;
import io.floci.oci.core.common.docker.ContainerSpec;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs the appliance that stands in for an OCI load balancer: a small container, from the cluster's
 * own k3s image (already pulled; it ships {@code /bin/aux/iptables}), that holds the load
 * balancer's addresses and DNATs each Service port to the backend nodes' NodePorts, picked at
 * random, masquerading so replies come back through it. It runs with {@code NET_ADMIN} and
 * {@code net.ipv4.ip_forward=1}, not privileged.
 *
 * <p>An internal load balancer's appliance sits on the cluster network at its private address. A
 * public one sits on the shared public network ({@code floci-oci-oke-lb-public}, created with the
 * first public load balancer and removed with the last) at its public address, and joins the
 * cluster network to reach the nodes, at its private address for an NLB. Every appliance also joins
 * floci-oci's own network when there is one, and publishes each Service port on the Docker host's
 * {@code 127.0.0.1} ({@code load-balancer.host-ports}).
 *
 * <p>A running appliance is kept when its {@link #SPEC_LABEL} matches what it should do; otherwise
 * it is replaced, asking for the host ports the old one had so they stay stable.
 */
@ApplicationScoped
public class OkeLoadBalancerApplianceManager implements OkeLoadBalancerAppliances {

    private static final Logger LOG = Logger.getLogger(OkeLoadBalancerApplianceManager.class);
    /** The Service UID an appliance serves. */
    static final String SERVICE_LABEL = "floci_oke_load_balancer";
    /** A digest of what the appliance does, so one that should do something else is replaced. */
    static final String SPEC_LABEL = "floci_oke_load_balancer_spec";
    /** Whether the appliance sits on the shared public network. */
    static final String PUBLIC_LABEL = "floci_oke_load_balancer_public";
    static final String HOST_PORTS_DYNAMIC = "dynamic";
    static final String HOST_PORTS_SAME_AS_SERVICE = "same-as-service";
    static final String HOST_PORTS_NONE = "none";
    private static final String IPTABLES = "/bin/aux/iptables";
    private static final Set<String> NETWORKS_NOT_JOINED = Set.of("host", "none");

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final OkeClusterManager clusterManager;
    private final EmulatorConfig config;

    @Inject
    public OkeLoadBalancerApplianceManager(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                           OkeClusterManager clusterManager, EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.clusterManager = clusterManager;
        this.config = config;
    }

    static String publicNetworkName(EmulatorConfig config) {
        return ContainerStorageHelper.dockerName(config, "oke-lb-public");
    }

    static void validateHostPorts(String value) {
        if (!Set.of(HOST_PORTS_DYNAMIC, HOST_PORTS_SAME_AS_SERVICE, HOST_PORTS_NONE).contains(value)) {
            throw new IllegalStateException("Invalid floci-oci.services.oke.load-balancer.host-ports value '" + value
                    + "': allowed values are dynamic, same-as-service and none");
        }
    }

    @Override
    public String nameFor(String serviceUid) {
        return ContainerStorageHelper.dockerName(config, "oke-lb-" + serviceUid);
    }

    @Override
    public Map<String, Integer> ensure(StoredOkeCluster cluster, Spec spec) {
        ContainerSpec wanted = containerSpec(cluster, spec, spec.preferredHostPorts(), true);
        String digest = wanted.labels().get(SPEC_LABEL);
        if (lifecycleManager.isContainerRunning(spec.name())
                && digest.equals(lifecycleManager.containerLabels(spec.name()).get(SPEC_LABEL))) {
            return lifecycleManager.publishedHostPorts(spec.name());
        }
        lifecycleManager.removeIfExists(spec.name());
        if (!spec.internal()) {
            ensurePublicNetwork();
        }
        try {
            lifecycleManager.createAndStart(wanted);
        } catch (RuntimeException first) {
            if (!wanted.publishedPorts().stream().anyMatch(port -> port.hostPort() != 0)) {
                throw addressInUseOr(first, spec);
            }
            LOG.debugv("Appliance {0} could not take host ports {1}, retrying with any: {2}", spec.name(),
                    spec.preferredHostPorts(), first.getMessage());
            try {
                lifecycleManager.createAndStart(containerSpec(cluster, spec, Map.of(), false));
            } catch (RuntimeException second) {
                throw addressInUseOr(second, spec);
            }
        }
        Map<String, Integer> hostPorts = lifecycleManager.publishedHostPorts(spec.name());
        LOG.infov("Started load balancer appliance {0} for Service {1} of cluster {2} at {3}, host ports {4}",
                spec.name(), spec.serviceUid(), cluster.getId(),
                spec.internal() ? spec.privateIp() : spec.publicIp(), hostPorts);
        return hostPorts;
    }

    @Override
    public void remove(StoredOkeCluster cluster, String name) {
        lifecycleManager.removeIfExists(name);
        clusterManager.removePublicLoadBalancerNetworkIfUnused();
    }

    @Override
    public Set<String> list(StoredOkeCluster cluster) {
        Set<String> names = new LinkedHashSet<>();
        for (Container container : lifecycleManager.listByLabel(OkeClusterManager.CLUSTER_LABEL, cluster.getId())) {
            Map<String, String> labels = container.getLabels() != null ? container.getLabels() : Map.of();
            if (labels.containsKey(SERVICE_LABEL) && container.getNames() != null && container.getNames().length > 0) {
                names.add(container.getNames()[0].startsWith("/")
                        ? container.getNames()[0].substring(1) : container.getNames()[0]);
            }
        }
        return names;
    }

    /**
     * The container for {@code spec}.
     *
     * @param hostPorts      host ports to ask for again, by {@code PROTOCOL/port}
     * @param honorHostPorts false to let Docker pick every host port, after the wished-for ones were taken
     */
    ContainerSpec containerSpec(StoredOkeCluster cluster, Spec spec, Map<String, Integer> hostPorts,
                                boolean honorHostPorts) {
        String script = script(spec);
        String clusterNetwork = clusterManager.networkName(cluster);
        String primary = spec.internal() ? clusterNetwork : publicNetworkName(config);
        String primaryAddress = spec.internal() ? spec.privateIp() : spec.publicIp();
        String image = clusterManager.serverImage(cluster);
        String hostPortsMode = config.services().oke().loadBalancer().hostPorts();

        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                "oke", spec.serviceUid(), cluster.getCompartmentId(),
                cluster.getRegion() != null ? cluster.getRegion() : config.defaultRegion()));
        labels.put(OkeClusterManager.CLUSTER_LABEL, cluster.getId());
        labels.put(SERVICE_LABEL, spec.serviceUid());
        labels.put(PUBLIC_LABEL, String.valueOf(!spec.internal()));
        ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                .withName(spec.name())
                .withEntrypoint(List.of("sh", "-c", script))
                .withPrimaryNetwork(primary)
                .withIpv4Address(primaryAddress)
                .withCapAdd("NET_ADMIN")
                .withSysctl("net.ipv4.ip_forward", "1")
                .withLogRotation();
        List<String> attachments = new ArrayList<>();
        if (!spec.internal()) {
            builder.withNetworkAttachment(clusterNetwork, spec.privateIp());
            attachments.add(clusterNetwork + "=" + spec.privateIp());
        }
        clusterManager.emulatorNetwork()
                .filter(network -> !NETWORKS_NOT_JOINED.contains(network) && !network.equals(primary))
                .ifPresent(network -> {
                    builder.withNetworkAttachment(network, null);
                    attachments.add(network);
                });
        if (!HOST_PORTS_NONE.equals(hostPortsMode)) {
            for (OkeLoadBalancerSpec.Port port : spec.ports()) {
                int hostPort = 0;
                if (honorHostPorts) {
                    hostPort = HOST_PORTS_SAME_AS_SERVICE.equals(hostPortsMode)
                            ? port.port() : hostPorts.getOrDefault(port.key(), 0);
                }
                builder.withPublishedPort(port.port(), port.protocol(), OkeLoadBalancerAddresses.HOST_PORT_ADDRESS,
                        hostPort);
            }
        }
        labels.put(SPEC_LABEL, digest(String.join("\n", image, primary, String.valueOf(primaryAddress),
                String.join(",", attachments), hostPortsMode, script)));
        return builder.withLabels(labels).build();
    }

    /** The appliance's {@code sh -c} script: the NAT rules, then sleep. Every value in it is an address or a number. */
    static String script(Spec spec) {
        StringBuilder script = new StringBuilder("set -e\nIPT=" + IPTABLES + "\n");
        List<String> backends = spec.backends();
        for (OkeLoadBalancerSpec.Port port : spec.ports()) {
            String protocol = port.protocol().toLowerCase(Locale.ROOT);
            List<String> sources = spec.sourceRanges().isEmpty() ? Collections.singletonList(null)
                    : spec.sourceRanges();
            for (String source : sources) {
                for (int i = 0; i < backends.size(); i++) {
                    script.append("$IPT -t nat -A PREROUTING -p ").append(protocol);
                    if (source != null) {
                        script.append(" -s ").append(source);
                    }
                    script.append(" --dport ").append(port.port());
                    if (i < backends.size() - 1) {
                        script.append(" -m statistic --mode random --probability ")
                                .append(String.format(Locale.ROOT, "%.10f", 1.0 / (backends.size() - i)));
                    }
                    script.append(" -j DNAT --to-destination ").append(backends.get(i)).append(':')
                            .append(port.nodePort()).append('\n');
                }
            }
            for (String backend : backends) {
                script.append("$IPT -t nat -A POSTROUTING -p ").append(protocol).append(" -d ").append(backend)
                        .append(" --dport ").append(port.nodePort()).append(" -j MASQUERADE\n");
            }
        }
        return script.append("exec sleep infinity\n").toString();
    }

    private void ensurePublicNetwork() {
        String cidr = config.services().oke().loadBalancer().publicCidr();
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(PUBLIC_LABEL, "true");
        lifecycleManager.ensureNetwork(publicNetworkName(config), cidr, OkeLoadBalancerAddresses.dockerRange(cidr),
                labels);
    }

    /** Docker refusing a fixed address someone else holds becomes {@link AddressInUseException}, so a new one is picked. */
    private static RuntimeException addressInUseOr(RuntimeException failure, Spec spec) {
        String message = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("address already in use") && !message.contains("bind")) {
            List<String> addresses = new ArrayList<>();
            if (spec.privateIp() != null) {
                addresses.add(spec.privateIp());
            }
            if (spec.publicIp() != null) {
                addresses.add(spec.publicIp());
            }
            return new AddressInUseException(addresses, failure);
        }
        return failure;
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
