package io.floci.oci.services.oke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Container;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.Resettable;
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
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Driver for real-mode rancher/k3s Docker sidecar containers managing local Kubernetes clusters.
 */
@ApplicationScoped
public class OkeClusterManager implements Resettable {

    private static final Logger LOG = Logger.getLogger(OkeClusterManager.class);
    private static final int K3S_CONTAINER_PORT = 6443;
    static final String API_TOKEN_ENV = "FLOCI_OKE_API_TOKEN";
    static final String WEBHOOK_KUBECONFIG_ENV = "FLOCI_OKE_WEBHOOK_KUBECONFIG";
    static final String TLS_SAN_ENV = "FLOCI_OKE_TLS_SAN";
    /** The k3s server flags, so a sidecar started with other flags is recreated rather than adopted. */
    static final String SERVER_ARGS_ENV = "FLOCI_OKE_SERVER_ARGS";
    /** Label naming the cluster on every container of a cluster, so its agents can be found and removed. */
    static final String CLUSTER_LABEL = "floci_oke_cluster";
    static final String NODE_POOL_LABEL = "floci_oke_node_pool";
    static final String NODE_VOLUME_LABEL = "floci_oke_node_volume";
    private static final Duration KUBECTL_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The third octets tried for a cluster's {@code 10.0.X.0/24} node network, from OKE's usual worker subnet up. */
    private static final int FIRST_SUBNET_OCTET = 10;
    private static final int LAST_SUBNET_OCTET = 250;
    static final String ENDPOINT_MODE_HOST = "host";
    static final String ENDPOINT_MODE_NETWORK = "network";
    /** Docker's built-in networks, which have no container-name lookup. */
    private static final Set<String> NETWORKS_WITHOUT_NAME_LOOKUP = Set.of("bridge", "host", "none");
    static final String MANIFESTS_ENV = "FLOCI_OKE_MANIFESTS";
    static final String MANIFEST_FILE = "/var/lib/rancher/k3s/server/manifests/floci-oke.yaml";
    /** Where every agent of a cluster mounts the shared volume behind the {@code oci} storage class. */
    static final String OCI_VOLUMES_PATH = "/var/lib/floci-oke/oci-volumes";
    static final String TOKEN_DIR = "/var/lib/rancher/k3s/floci";
    static final String TOKEN_FILE = TOKEN_DIR + "/tokens.csv";
    static final String WEBHOOK_FILE = TOKEN_DIR + "/token-webhook.yaml";
    static final String WEBHOOK_PATH_PREFIX = "/_floci-oci/oke/token-webhook/";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    /**
     * Writes the k3s static token file from {@link #API_TOKEN_ENV} and the token-webhook
     * kubeconfig from {@link #WEBHOOK_KUBECONFIG_ENV}, then execs k3s with the container CMD plus a
     * serving-certificate SAN for the container name in {@link #TLS_SAN_ENV}, so
     * {@code endpoint-mode=network} endpoints verify. k3s adds a new SAN to the certificate it keeps
     * on the volume when it restarts, so a recreated sidecar keeps its CA.
     * Both files are rewritten from the container's env on every start; the webhook address in
     * that env is fixed when the container is created. The image's own CMD is {@code agent}, which needs a server to join
     * and exits at once, so {@link #buildCmd} always supplies {@code server}. Values travel as env
     * vars so nothing from the cluster record is ever interpolated into the script.
     */
    static final List<String> K3S_ENTRYPOINT = List.of("sh", "-c",
            "umask 077 && mkdir -p " + TOKEN_DIR
                    + " && printf '%s,floci-admin,floci-admin,system:masters\\n' \"$" + API_TOKEN_ENV + "\""
                    + " > " + TOKEN_FILE
                    + " && printf '%s\\n' \"$" + WEBHOOK_KUBECONFIG_ENV + "\""
                    + " > " + WEBHOOK_FILE
                    + " && mkdir -p " + MANIFEST_FILE.substring(0, MANIFEST_FILE.lastIndexOf('/'))
                    + " && printf '%s\\n' \"$" + MANIFESTS_ENV + "\" > " + MANIFEST_FILE
                    + " && exec /bin/k3s \"$@\" --tls-san=\"$" + TLS_SAN_ENV + "\"");

    public record ActiveClusterRef(String containerName, String volumeName, int hostPort) {}

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final PortAllocator portAllocator;
    private final DockerHostResolver dockerHostResolver;
    private final ContainerDetector containerDetector;
    private final CurrentContainerNetworkResolver networkResolver;
    private final EmulatorConfig config;

    private final Map<String, ActiveClusterRef> activeClusters = new ConcurrentHashMap<>();
    private volatile ContainerLifecycleManager.HostResources hostResources;

    @Inject
    public OkeClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             PortAllocator portAllocator,
                             DockerHostResolver dockerHostResolver,
                             ContainerDetector containerDetector,
                             CurrentContainerNetworkResolver networkResolver,
                             EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.portAllocator = portAllocator;
        this.dockerHostResolver = dockerHostResolver;
        this.containerDetector = containerDetector;
        this.networkResolver = networkResolver;
        this.config = config;
    }

    /**
     * Starts (or adopts) the k3s sidecar and sets the cluster endpoints. The lifecycle state is
     * left to the caller: the cluster becomes ACTIVE only once {@link #probeReady} succeeds.
     */
    public void startCluster(StoredOkeCluster cluster) {
        if (cluster.getApiToken() == null || cluster.getApiToken().isBlank()) {
            throw new IllegalStateException("OKE cluster " + cluster.getId() + " has no API token");
        }

        int hostPort = cluster.getHostPort();
        if (hostPort > 0) {
            portAllocator.markReserved(hostPort);
        } else {
            int basePort = config.services().oke().apiServerBasePort();
            int maxPort = config.services().oke().apiServerMaxPort();
            hostPort = portAllocator.allocate(basePort, maxPort);
            cluster.setHostPort(hostPort);
        }

        String containerName = containerName(cluster);
        String volumeName = ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());

        activeClusters.put(cluster.getId(), new ActiveClusterRef(containerName, volumeName, hostPort));
        List<String> serverArgs = buildServerArgs(cluster);

        // A volume from an earlier start holds the cluster's data and CA: a failed start removes the
        // volume only when it was confirmed absent before this start created it.
        boolean createdVolume = false;
        try {
            String network = ensureClusterNetwork(cluster);
            if (!lifecycleManager.isContainerRunning(containerName)
                    || !createdForThisCluster(containerName, cluster, serverArgs)) {
                createdVolume = lifecycleManager.volumeConfirmedAbsent(volumeName);
                lifecycleManager.removeIfExists(containerName);
                // Created up front so it carries the floci labels; a mount alone creates it bare.
                lifecycleManager.ensureVolume(volumeName);

                ContainerSpec spec = containerBuilder.newContainer(serverImage(cluster))
                        .withName(containerName)
                        .withEntrypoint(K3S_ENTRYPOINT)
                        .withCmd(buildCmd(serverArgs))
                        .withEnv("K3S_KUBECONFIG_MODE", "644")
                        .withEnv(API_TOKEN_ENV, cluster.getApiToken())
                        .withEnv(WEBHOOK_KUBECONFIG_ENV, webhookKubeconfig(cluster))
                        .withEnv(TLS_SAN_ENV, containerName)
                        .withEnv(SERVER_ARGS_ENV, String.join(" ", serverArgs))
                        .withEnv(MANIFESTS_ENV, OkeManifests.clusterManifests())
                        .withPortBinding(K3S_CONTAINER_PORT, hostPort)
                        .withNamedVolume(volumeName, "/var/lib/rancher/k3s")
                        .withPrimaryNetwork(network)
                        .withDockerNetwork(Optional.empty())
                        .withHostDockerInternalOnLinux()
                        .withPrivileged(true)
                        .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                                "oke", cluster.getId(), cluster.getCompartmentId(), clusterRegion(cluster)))
                        .withLabel(CLUSTER_LABEL, cluster.getId())
                        .build();

                lifecycleManager.createAndStart(spec);
            }
        } catch (Exception e) {
            activeClusters.remove(cluster.getId());
            try {
                lifecycleManager.removeIfExists(containerName);
                if (createdVolume) {
                    lifecycleManager.removeVolume(volumeName);
                }
            } catch (Exception cleanupEx) {
                LOG.warnf(cleanupEx, "Failed to clean up container '%s' or volume '%s' after startup failure", containerName, volumeName);
            }
            portAllocator.release(hostPort);
            cluster.setHostPort(0);
            cluster.setLifecycleState("FAILED");
            LOG.errorf("Failed to start k3s sidecar container '%s' on port %d: %s", containerName, hostPort, e.getMessage());
            throw e;
        }

        cluster.setEndpoints(Map.of(
            "kubernetes", kubernetesEndpoint(containerName, hostPort),
            "privateEndpoint", "10.0.0.10:" + hostPort
        ));
        LOG.infof("Started k3s sidecar container '%s' bound to host port %d", containerName, hostPort);
    }

    /**
     * The API server address {@code endpoints.kubernetes} and the kubeconfig advertise. In
     * {@code endpoint-mode=network}, when floci-oci itself runs in a container, it is the sidecar's
     * container name on the Docker network they share, so other containers on that network reach it;
     * otherwise, and always when floci-oci runs on the host, it is the published host port. A name
     * needs a user-defined network to resolve: on Docker's default {@code bridge} (or {@code host},
     * {@code none}) peers cannot look it up, so network mode falls back to the host port there too.
     */
    String kubernetesEndpoint(String containerName, int hostPort) {
        if (containerDetector.isRunningInContainer()
                && ENDPOINT_MODE_NETWORK.equalsIgnoreCase(config.services().oke().endpointMode())) {
            Optional<String> network = sidecarNetwork();
            if (network.isPresent() && !NETWORKS_WITHOUT_NAME_LOOKUP.contains(network.get())) {
                return "https://" + containerName + ":" + K3S_CONTAINER_PORT;
            }
            LOG.warnv("endpoint-mode=network needs floci-oci on a user-defined Docker network so other containers"
                    + " can resolve {0}, but its sidecar network is {1}; advertising the host port instead",
                    containerName, network.orElse("unknown"));
        }
        return "https://127.0.0.1:" + hostPort;
    }

    static void validateEndpointMode(String mode) {
        if (!ENDPOINT_MODE_HOST.equalsIgnoreCase(mode) && !ENDPOINT_MODE_NETWORK.equalsIgnoreCase(mode)) {
            throw new IllegalStateException("Invalid floci-oci.services.oke.endpoint-mode value '" + mode
                    + "': allowed values are " + ENDPOINT_MODE_HOST + " and " + ENDPOINT_MODE_NETWORK);
        }
    }

    /**
     * The cluster's own Docker network, on which its k3s server and agents find each other by name
     * and the agents take fixed addresses, as OKE worker nodes have fixed private IPs. It gets a
     * {@code 10.0.X.0/24} subnet, starting from {@code 10.0.10.0/24} (OKE's quick-create worker
     * subnet), skipping any subnet another Docker network holds. Docker hands out the first
     * sixteen addresses (the gateway and the server); nodes take fixed ones from {@code .16}.
     */
    String ensureClusterNetwork(StoredOkeCluster cluster) {
        String network = networkName(cluster);
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                "oke", cluster.getId(), cluster.getCompartmentId(), clusterRegion(cluster)));
        labels.put(CLUSTER_LABEL, cluster.getId());
        Optional<String> existing = lifecycleManager.networkSubnet(network);
        if (existing.isPresent()) {
            if (cluster.getNodeSubnet() == null && !existing.get().isBlank()) {
                cluster.setNodeSubnet(existing.get());
            }
            return network;
        }
        List<String> candidates = new ArrayList<>();
        if (cluster.getNodeSubnet() != null) {
            candidates.add(cluster.getNodeSubnet());
        }
        for (int octet = FIRST_SUBNET_OCTET; octet <= LAST_SUBNET_OCTET; octet++) {
            String subnet = "10.0." + octet + ".0/24";
            if (!candidates.contains(subnet)) {
                candidates.add(subnet);
            }
        }
        RuntimeException lastFailure = null;
        for (String subnet : candidates) {
            try {
                String ipRange = subnet.substring(0, subnet.lastIndexOf('.')) + ".0/28";
                cluster.setNodeSubnet(lifecycleManager.ensureNetwork(network, subnet, ipRange, labels));
                return network;
            } catch (RuntimeException e) {
                LOG.debugv("Subnet {0} is not free for cluster {1}: {2}", subnet, cluster.getId(), e.getMessage());
                lastFailure = e;
            }
        }
        throw new IllegalStateException("No free 10.0.X.0/24 subnet for the Docker network of cluster "
                + cluster.getId(), lastFailure);
    }

    String networkName(StoredOkeCluster cluster) {
        return ContainerStorageHelper.dockerName(config, "oke-net-" + cluster.getId());
    }

    /**
     * Tokens k3s does not know (the {@code oci ce cluster generate-token} ones) go to
     * floci-oci's token webhook. {@code 127.0.0.1} and {@code localhost} are already in k3s's
     * default certificate SANs, which covers the host-mode endpoint; {@link #K3S_ENTRYPOINT} adds the
     * container name for network mode. The server runs no kubelet ({@code --disable-agent}) and
     * reaches the kubelets and pods through the agents' tunnels ({@code --egress-selector-mode=cluster}),
     * so {@code kubectl get nodes} lists only the worker nodes, as on a managed control plane.
     */
    static List<String> buildServerArgs(StoredOkeCluster cluster) {
        return List.of("server",
                "--disable-agent",
                "--egress-selector-mode=cluster",
                "--disable=traefik",
                "--disable=local-storage",
                "--kube-apiserver-arg=token-auth-file=" + TOKEN_FILE,
                "--kube-apiserver-arg=authentication-token-webhook-config-file=" + WEBHOOK_FILE,
                "--kube-apiserver-arg=authentication-token-webhook-version=v1",
                "--kube-apiserver-arg=authentication-token-webhook-cache-ttl=30s");
    }

    /**
     * The CMD paired with {@link #K3S_ENTRYPOINT}: a {@code $0} placeholder, then the k3s
     * server args that the script's {@code "$@"} expands to.
     */
    static List<String> buildCmd(List<String> serverArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add("k3s");
        cmd.addAll(serverArgs);
        return cmd;
    }

    /**
     * A running sidecar is adopted only when it was created with this cluster's token, current
     * webhook address, container-name SAN and region label. One left by an older floci-oci, or by
     * one reachable at another address, would reject the exec credentials, serve a certificate
     * without the network-mode name, or stay labelled with the wrong region, so it is recreated on
     * the same volume, keeping its CA.
     */
    private boolean createdForThisCluster(String containerName, StoredOkeCluster cluster, List<String> serverArgs) {
        List<String> env = lifecycleManager.containerEnv(containerName);
        return serverImage(cluster).equals(lifecycleManager.containerImage(containerName))
                && env.contains(SERVER_ARGS_ENV + "=" + String.join(" ", serverArgs))
                && env.contains(MANIFESTS_ENV + "=" + OkeManifests.clusterManifests())
                && env.contains(API_TOKEN_ENV + "=" + cluster.getApiToken())
                && env.contains(WEBHOOK_KUBECONFIG_ENV + "=" + webhookKubeconfig(cluster))
                && env.contains(TLS_SAN_ENV + "=" + containerName)
                && clusterRegion(cluster).equals(
                        lifecycleManager.containerLabels(containerName).get(ContainerStorageHelper.REGION_LABEL));
    }

    /**
     * The kubeconfig k3s's API server uses to POST {@code TokenReview}s to floci-oci. The scope
     * (tenancy, cluster) is in the path, never the query: client-go replaces a server URL's query
     * when it builds the request. Plain http, as floci-aws does: floci-oci keeps serving HTTP on
     * its port even with TLS enabled, and this channel never leaves the Docker host.
     */
    String webhookKubeconfig(StoredOkeCluster cluster) {
        String url = "http://" + dockerHostResolver.resolve() + ":" + config.port() + webhookPath(cluster);
        return """
                apiVersion: v1
                kind: Config
                clusters:
                - name: floci-oci-token-webhook
                  cluster:
                    server: %s
                users:
                - name: floci-oci-token-webhook
                contexts:
                - name: floci-oci-token-webhook
                  context:
                    cluster: floci-oci-token-webhook
                    user: floci-oci-token-webhook
                current-context: floci-oci-token-webhook
                """.formatted(url);
    }

    static String webhookPath(StoredOkeCluster cluster) {
        return WEBHOOK_PATH_PREFIX + cluster.getTenancyId() + "/" + cluster.getId();
    }

    /** The k3s image of the cluster's Kubernetes version. */
    String serverImage(StoredOkeCluster cluster) {
        return containerBuilder.resolveImage(
                OkeVersions.image(config.services().oke().imageRepository(), cluster.getKubernetesVersion()));
    }

    /**
     * Replaces the k3s server with one on the cluster's current version, keeping its volume, host
     * port and so its data and CA. Used by control plane upgrades.
     */
    public void restartCluster(StoredOkeCluster cluster) {
        lifecycleManager.removeIfExists(containerName(cluster));
        startCluster(cluster);
    }

    /** Whether the cluster's sidecar container is running. */
    public boolean isClusterRunning(StoredOkeCluster cluster) {
        return lifecycleManager.isContainerRunning(containerName(cluster));
    }

    /**
     * One readiness probe. Fetches the server CA from k3s's unauthenticated {@code /cacerts}
     * (the same bootstrap k3s agents use, so it cannot be verified yet), then calls
     * {@code /readyz} with the cluster's static token, verifying the certificate chain against
     * that CA. Returns the CA as base64 PEM, the form a kubeconfig's
     * {@code certificate-authority-data} takes, once both answer 200.
     */
    public Optional<String> probeReady(StoredOkeCluster cluster) {
        try {
            String base = apiServerUrl(cluster);
            Optional<String> caPem = get(tlsContext(new TrustAnyCertificate()), base + "/cacerts", null);
            if (caPem.isEmpty()) {
                return Optional.empty();
            }
            Optional<String> ready = get(tlsContext(new TrustClusterCa(caPem.get())), base + "/readyz",
                    cluster.getApiToken());
            if (ready.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(Base64.getEncoder().encodeToString(caPem.get().getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | GeneralSecurityException e) {
            LOG.debugv("k3s API for cluster {0} not ready: {1}", cluster.getId(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Where floci-oci itself reaches the API server: the published host port when floci-oci runs
     * on the host; otherwise the sidecar's IP on the network it was attached to, read from the
     * container rather than looked up by name. The name embeds the OCID, whose last label can start
     * with a digit, which {@link URI} rejects as a host, and Docker's default {@code bridge}
     * network has no name lookup at all.
     */
    String apiServerUrl(StoredOkeCluster cluster) throws IOException {
        if (!containerDetector.isRunningInContainer()) {
            return "https://127.0.0.1:" + cluster.getHostPort();
        }
        ContainerLifecycleManager.EndpointInfo endpoint;
        try {
            endpoint = lifecycleManager.resolveEndpoint(containerName(cluster), K3S_CONTAINER_PORT,
                    sidecarNetwork().orElse(null));
        } catch (RuntimeException e) {
            throw new IOException("Cannot inspect the k3s sidecar of cluster " + cluster.getId(), e);
        }
        String host = endpoint.host().contains(":") ? "[" + endpoint.host() + "]" : endpoint.host();
        return "https://" + host + ":" + endpoint.port();
    }

    /**
     * The network {@code ContainerBuilder.withDockerNetwork(Optional.empty())} attaches the sidecar
     * to: the configured {@code services.docker-network}, else floci-oci's own. A sidecar with a
     * published port also sits on {@code bridge}, which floci-oci cannot reach from its network.
     */
    private Optional<String> sidecarNetwork() {
        return config.services().dockerNetwork()
                .filter(n -> !n.isBlank())
                .or(networkResolver::resolveNetworkName);
    }

    /** The region the cluster was created in; clusters persisted before regions were recorded use the default. */
    private String clusterRegion(StoredOkeCluster cluster) {
        return cluster.getRegion() != null ? cluster.getRegion() : config.defaultRegion();
    }

    String containerName(StoredOkeCluster cluster) {
        return ContainerStorageHelper.dockerName(config, "oke-" + cluster.getId());
    }

    private static Optional<String> get(SSLContext sslContext, String url, String bearerToken)
            throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(PROBE_TIMEOUT)
                .build()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(PROBE_TIMEOUT).GET();
            if (bearerToken != null) {
                request.header("Authorization", "Bearer " + bearerToken);
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        }
    }

    private static SSLContext tlsContext(TrustManager trustManager) throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trustManager}, null);
        return context;
    }

    /**
     * Bootstrap only: the CA this lets floci-oci fetch is what the next call is verified against.
     * Extends {@link X509ExtendedTrustManager} so the JDK does not add its own host name check.
     */
    private static final class TrustAnyCertificate extends X509ExtendedTrustManager {
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Accepted: see the class comment.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
            // Accepted: see the class comment.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // Accepted: see the class comment.
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            throw new UnsupportedOperationException("floci-oci is the client here");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            throw new UnsupportedOperationException("floci-oci is the client here");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            throw new UnsupportedOperationException("floci-oci is the client here");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /**
     * Verifies the server's certificate chain against the cluster's own CA, without matching the
     * host name: inside Docker floci-oci dials the sidecar's IP, which k3s's certificate does not
     * list because the shared network is attached after k3s starts.
     */
    private static final class TrustClusterCa extends X509ExtendedTrustManager {
        private final X509TrustManager delegate;

        TrustClusterCa(String caPem) throws GeneralSecurityException, IOException {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certificates =
                    factory.generateCertificates(new ByteArrayInputStream(caPem.getBytes(StandardCharsets.UTF_8)));
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (Certificate certificate : certificates) {
                trustStore.setCertificateEntry("k3s-ca-" + index++, certificate);
            }
            TrustManagerFactory factoryForCa = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factoryForCa.init(trustStore);
            this.delegate = (X509TrustManager) factoryForCa.getTrustManagers()[0];
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            throw new UnsupportedOperationException("floci-oci is the client here");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            throw new UnsupportedOperationException("floci-oci is the client here");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            throw new UnsupportedOperationException("floci-oci is the client here");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    // ── Worker nodes (k3s agents) ────────────────────────────────────────────

    String nodeContainerName(StoredNode node) {
        return ContainerStorageHelper.dockerName(config, "oke-node-" + node.getId());
    }

    String nodeVolumeName(StoredNode node) {
        return ContainerStorageHelper.dockerName(config, "oke-node-vol-" + node.getId());
    }

    /**
     * The volume every agent of a cluster mounts at {@link #OCI_VOLUMES_PATH}: the {@code oci} storage
     * class binds at once ({@code Immediate}), before any node is chosen, so its volumes must be
     * reachable from every node.
     */
    String ociVolumesName(StoredOkeCluster cluster) {
        return ContainerStorageHelper.dockerName(config, "oke-oci-vol-" + cluster.getId());
    }

    /**
     * Starts the k3s agent behind a node, or adopts the one already running for it. The agent joins
     * the cluster's server by container name on the cluster network, at the node's fixed address,
     * registers under that address as its node name (OKE names managed nodes after their private IP)
     * with the OKE node labels and an {@code oci://} provider ID, and keeps its k3s state, including
     * the node's local volumes, on its own named volume.
     *
     * @return whether a new container was created
     */
    public boolean ensureNode(StoredOkeCluster cluster, StoredNodePool pool, StoredNode node) {
        String containerName = nodeContainerName(node);
        String image = containerBuilder.resolveImage(
                OkeVersions.image(config.services().oke().imageRepository(), node.getKubernetesVersion()));
        String serverUrl = "https://" + containerName(cluster) + ":" + K3S_CONTAINER_PORT;
        if (lifecycleManager.isContainerRunning(containerName)
                && createdForNode(containerName, node, image, serverUrl, cluster.getJoinToken())) {
            return false;
        }
        lifecycleManager.removeIfExists(containerName);
        // A recreated agent has a new node password; k3s refuses it for a known node name otherwise.
        kubectl(cluster, List.of("-n", "kube-system", "delete", "secret",
                node.getPrivateIp() + ".node-password.k3s", "--ignore-not-found"));
        String volumeName = nodeVolumeName(node);
        lifecycleManager.ensureVolume(volumeName);
        String ociVolumes = ociVolumesName(cluster);
        lifecycleManager.ensureVolume(ociVolumes);

        OkeShapes.Size size = OkeShapes.size(pool.getNodeShape(), pool.getNodeShapeConfig());
        List<String> args = new ArrayList<>(List.of("agent",
                "--node-name=" + node.getPrivateIp(),
                "--node-ip=" + node.getPrivateIp(),
                "--kubelet-arg=provider-id=" + OkeNodeLabels.providerId(node)));
        OkeNodeLabels.registrationLabels(pool, node, clusterRegion(cluster))
                .forEach((key, value) -> args.add("--node-label=" + key + "=" + value));
        if (size.gpus() > 0) {
            args.add("--node-taint=" + OkeNodeLabels.GPU_TAINT);
        }
        Optional<OkeNodeCapacity.Limits> limits = OkeNodeCapacity.calculate(size, hostResources());
        limits.ifPresent(l -> args.addAll(l.kubeletArgs()));

        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                "oke", node.getId(), pool.getCompartmentId(), clusterRegion(cluster)));
        labels.put(CLUSTER_LABEL, cluster.getId());
        labels.put(NODE_POOL_LABEL, pool.getId());
        labels.put(NODE_VOLUME_LABEL, volumeName);
        ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withCmd(args)
                .withEnv("K3S_URL", serverUrl)
                .withEnv("K3S_TOKEN", cluster.getJoinToken())
                .withPrimaryNetwork(networkName(cluster))
                .withIpv4Address(node.getPrivateIp())
                .withNamedVolume(volumeName, "/var/lib/rancher/k3s")
                .withNamedVolume(ociVolumes, OCI_VOLUMES_PATH)
                .withPrivileged(true)
                .withLogRotation()
                .withLabels(labels);
        limits.ifPresent(l -> builder.withMemoryBytes(l.memoryBytes()).withNanoCpus(l.nanoCpus()));
        lifecycleManager.createAndStart(builder.build());
        LOG.infov("Started k3s agent {0} for node {1} ({2}) of node pool {3}", containerName, node.getName(),
                node.getPrivateIp(), pool.getId());
        return true;
    }

    private boolean createdForNode(String containerName, StoredNode node, String image, String serverUrl,
                                   String joinToken) {
        List<String> env = lifecycleManager.containerEnv(containerName);
        return image.equals(lifecycleManager.containerImage(containerName))
                && env.contains("K3S_URL=" + serverUrl)
                && env.contains("K3S_TOKEN=" + joinToken)
                && node.getId().equals(lifecycleManager.containerLabels(containerName)
                        .get(ContainerStorageHelper.RESOURCE_ID_LABEL));
    }

    /**
     * Removes a node: its Kubernetes Node object (so pods move on, as when OKE terminates an
     * instance), its agent container and, with {@code dropVolume}, its volume.
     */
    public void removeNode(StoredOkeCluster cluster, StoredNode node, boolean dropVolume) {
        if (node.getPrivateIp() != null && isClusterRunning(cluster)) {
            kubectl(cluster, List.of("delete", "node", node.getPrivateIp(), "--ignore-not-found", "--wait=false"));
        }
        lifecycleManager.removeIfExists(nodeContainerName(node));
        if (dropVolume) {
            lifecycleManager.removeVolume(nodeVolumeName(node));
        }
    }

    /** The token agents join with, from the server's data volume. */
    public Optional<String> readJoinToken(StoredOkeCluster cluster) {
        ContainerLifecycleManager.ExecResult result = lifecycleManager.exec(containerName(cluster), null,
                List.of("cat", "/var/lib/rancher/k3s/server/token"), KUBECTL_TIMEOUT);
        String token = result.stdout().trim();
        return result.succeeded() && !token.isEmpty() ? Optional.of(token) : Optional.empty();
    }

    /** The cluster's Kubernetes nodes by name, and whether each is Ready; empty when the API cannot be read. */
    public Optional<Map<String, Boolean>> nodeReadiness(StoredOkeCluster cluster) {
        ContainerLifecycleManager.ExecResult result = kubectl(cluster, List.of("get", "nodes", "-o", "json"));
        if (!result.succeeded()) {
            return Optional.empty();
        }
        try {
            Map<String, Boolean> readiness = new LinkedHashMap<>();
            for (JsonNode item : JSON.readTree(result.stdout()).path("items")) {
                boolean ready = false;
                for (JsonNode condition : item.path("status").path("conditions")) {
                    if ("Ready".equals(condition.path("type").asText())) {
                        ready = "True".equals(condition.path("status").asText());
                    }
                }
                readiness.put(item.path("metadata").path("name").asText(), ready);
            }
            return Optional.of(readiness);
        } catch (IOException e) {
            LOG.warnv("Unreadable node list from cluster {0}: {1}", cluster.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * What OKE adds once a node has joined and a kubelet cannot set on itself: the {@code node} role
     * and {@code initialNodeLabels} in the Kubernetes namespaces, and the compartment annotation. A
     * GPU shape's node also advertises its GPUs as {@code nvidia.com/gpu}, which on OKE the NVIDIA
     * device plugin does; there is no GPU here, so the capacity is set on the node status directly.
     *
     * @return whether every change applied
     */
    public boolean finishNode(StoredOkeCluster cluster, StoredNodePool pool, StoredNode node) {
        List<String> label = new ArrayList<>(List.of("label", "node", node.getPrivateIp(), "--overwrite"));
        OkeNodeLabels.postRegistrationLabels(pool).forEach((key, value) -> label.add(key + "=" + value));
        List<String> annotate = new ArrayList<>(List.of("annotate", "node", node.getPrivateIp(), "--overwrite"));
        OkeNodeLabels.annotations(pool).forEach((key, value) -> annotate.add(key + "=" + value));
        boolean applied = kubectl(cluster, label).succeeded() && kubectl(cluster, annotate).succeeded();
        int gpus = OkeShapes.size(pool.getNodeShape(), pool.getNodeShapeConfig()).gpus();
        if (applied && gpus > 0) {
            String quantity = "\"" + OkeNodeLabels.GPU_RESOURCE + "\":\"" + gpus + "\"";
            applied = kubectl(cluster, List.of("patch", "node", node.getPrivateIp(), "--subresource=status",
                    "--type=merge", "-p", "{\"status\":{\"capacity\":{" + quantity + "},\"allocatable\":{"
                            + quantity + "}}}")).succeeded();
        }
        return applied;
    }

    /**
     * Deletes the Kubernetes nodes no node pool has (anymore): the server's own node in a cluster
     * started before the control plane ran agentless, and nodes whose agent is gone.
     */
    public void deleteStaleNodes(StoredOkeCluster cluster, Set<String> expected, Map<String, Boolean> present) {
        for (String name : present.keySet()) {
            if (!expected.contains(name)) {
                LOG.infov("Deleting Kubernetes node {0} of cluster {1}: no node pool has it", name, cluster.getId());
                kubectl(cluster, List.of("delete", "node", name, "--ignore-not-found", "--wait=false"));
            }
        }
    }

    /** Every agent of the cluster, found by label so one started during a delete goes too. */
    private void removeClusterNodes(String clusterId, boolean dropVolumes) {
        for (Container container : lifecycleManager.listByLabel(CLUSTER_LABEL, clusterId)) {
            Map<String, String> labels = container.getLabels() != null ? container.getLabels() : Map.of();
            String volume = labels.get(NODE_VOLUME_LABEL);
            if (volume == null) {
                continue;
            }
            lifecycleManager.removeIfExists(container.getId());
            if (dropVolumes || prunesVolumesOnStop()) {
                lifecycleManager.removeVolume(volume);
            } else {
                LOG.infof("Retained Docker volume '%s'. Remove manually: docker volume rm %s", volume, volume);
            }
        }
    }

    private ContainerLifecycleManager.ExecResult kubectl(StoredOkeCluster cluster, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("kubectl");
        command.addAll(args);
        try {
            ContainerLifecycleManager.ExecResult result =
                    lifecycleManager.exec(containerName(cluster), null, command, KUBECTL_TIMEOUT);
            if (!result.succeeded()) {
                LOG.debugv("kubectl {0} on cluster {1} failed: {2}", args, cluster.getId(), result.stderr());
            }
            return result;
        } catch (RuntimeException e) {
            LOG.debugv("kubectl {0} on cluster {1} failed: {2}", args, cluster.getId(), e.getMessage());
            return new ContainerLifecycleManager.ExecResult(-1, "", String.valueOf(e.getMessage()));
        }
    }

    private ContainerLifecycleManager.HostResources hostResources() {
        if (hostResources == null) {
            hostResources = lifecycleManager.hostResources().orElse(null);
        }
        return hostResources;
    }

    public void registerExistingCluster(StoredOkeCluster cluster) {
        if (cluster == null || cluster.getId() == null) {
            return;
        }
        startCluster(cluster);
    }

    public void stopCluster(StoredOkeCluster cluster) {
        ActiveClusterRef ref = activeClusters.get(cluster.getId());
        String containerName = ref != null ? ref.containerName() : containerName(cluster);
        String volumeName = ref != null ? ref.volumeName() : ContainerStorageHelper.dockerName(config, "oke-vol-" + cluster.getId());
        int hostPort = ref != null && ref.hostPort() > 0 ? ref.hostPort() : cluster.getHostPort();

        removeClusterNodes(cluster.getId(), false);
        lifecycleManager.removeIfExists(containerName);

        try {
            removeVolumeIfConfigured(ociVolumesName(cluster));
            removeVolumeIfConfigured(volumeName);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to remove volume '%s' for cluster '%s'", volumeName, cluster.getId());
        }

        if (hostPort > 0) {
            portAllocator.release(hostPort);
        }
        activeClusters.remove(cluster.getId());
        lifecycleManager.removeNetwork(networkName(cluster));
        LOG.infof("Stopped k3s sidecar container '%s' and released host port %d", containerName, hostPort);
    }

    /** Stops every sidecar; volumes follow the delete policy, so persistent clusters come back. */
    @PreDestroy
    void shutdown() {
        removeAll(false);
    }

    /**
     * A state reset forgets every cluster, so every volume goes too, whatever
     * {@code prune-volumes-on-delete} says: nothing could reach them afterwards.
     */
    @Override
    public void clear() {
        removeAll(true);
    }

    private void removeAll(boolean dropVolumes) {
        for (Map.Entry<String, ActiveClusterRef> entry : activeClusters.entrySet()) {
            ActiveClusterRef ref = entry.getValue();
            try {
                removeClusterNodes(entry.getKey(), dropVolumes);
                lifecycleManager.removeIfExists(ref.containerName());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to remove k3s sidecar container '%s'", ref.containerName());
            }
            lifecycleManager.removeNetwork(ContainerStorageHelper.dockerName(config, "oke-net-" + entry.getKey()));
            try {
                String ociVolumes = ContainerStorageHelper.dockerName(config, "oke-oci-vol-" + entry.getKey());
                if (dropVolumes) {
                    lifecycleManager.removeVolume(ref.volumeName());
                    lifecycleManager.removeVolume(ociVolumes);
                } else {
                    removeVolumeIfConfigured(ref.volumeName());
                    removeVolumeIfConfigured(ociVolumes);
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to remove k3s volume '%s'", ref.volumeName());
            }
            try {
                portAllocator.release(ref.hostPort());
                LOG.infof("Cleaned up k3s sidecar container '%s' and released host port %d", ref.containerName(), ref.hostPort());
            } catch (Exception e) {
                LOG.warnf(e, "Failed to release host port %d for container '%s'", ref.hostPort(), ref.containerName());
            }
        }
        activeClusters.clear();
    }

    /** Whether stopping a cluster, including at shutdown, removes its volume and so its k3s CA. */
    boolean prunesVolumesOnStop() {
        return "memory".equalsIgnoreCase(config.storage().mode()) || config.storage().pruneVolumesOnDelete();
    }

    private void removeVolumeIfConfigured(String volumeName) {
        if (prunesVolumesOnStop()) {
            lifecycleManager.removeVolume(volumeName);
        } else {
            LOG.infof("Retained Docker volume '%s'. Remove manually: docker volume rm %s", volumeName, volumeName);
        }
    }
}
