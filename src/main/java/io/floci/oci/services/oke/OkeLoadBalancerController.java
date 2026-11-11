package io.floci.oci.services.oke;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.oci.services.oke.model.StoredLoadBalancer;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * floci-oci's stand-in for OKE's cloud controller manager, for Services of type LoadBalancer. Each
 * pass reads the cluster's Services, Nodes and EndpointSlices and, as kubernetes/cloud-provider's
 * service controller does ({@code syncLoadBalancerIfNeeded}): adds the
 * {@code service.kubernetes.io/load-balancer-cleanup} finalizer before anything else, gives the
 * load balancer its addresses and an appliance container holding them, writes the OCI-shaped
 * {@code status.loadBalancer}, and reports {@code EnsuringLoadBalancer}, {@code EnsuredLoadBalancer},
 * {@code SyncLoadBalancerFailed}, {@code UpdatedLoadBalancer}, {@code UnAvailableLoadBalancer},
 * {@code DeletingLoadBalancer} and {@code DeletedLoadBalancer} events with the upstream messages.
 * When a Service is deleted or stops being a LoadBalancer, it removes the appliance, releases the
 * addresses and drops the finalizer. A pass that changes nothing writes nothing.
 *
 * <p>Backends are the Ready nodes floci-oci runs as k3s agents that are not labelled
 * {@code node.kubernetes.io/exclude-from-external-load-balancers}; with
 * {@code externalTrafficPolicy: Local}, only those with a ready endpoint of the Service. There are no
 * health checks, so a node that is not Ready is left out rather than probed.
 */
final class OkeLoadBalancerController {

    private static final Logger LOG = Logger.getLogger(OkeLoadBalancerController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String EXCLUDE_LABEL = "node.kubernetes.io/exclude-from-external-load-balancers";
    private static final String NORMAL = "Normal";
    private static final String WARNING = "Warning";

    private final OkeLoadBalancerKube kube;
    private final OkeLoadBalancerAppliances appliances;
    private final OkeLoadBalancerRecords records;
    private final Clock clock;
    private final IntSupplier provisioningDelaySeconds;
    /** The last SyncLoadBalancerFailed message per Service UID, so a failure is reported once, not every pass. */
    private final Map<String, String> reportedFailures = new ConcurrentHashMap<>();

    OkeLoadBalancerController(OkeLoadBalancerKube kube, OkeLoadBalancerAppliances appliances,
                              OkeLoadBalancerRecords records, Clock clock, IntSupplier provisioningDelaySeconds) {
        this.kube = kube;
        this.appliances = appliances;
        this.records = records;
        this.clock = clock;
        this.provisioningDelaySeconds = provisioningDelaySeconds;
    }

    /**
     * One pass over the cluster.
     *
     * @param agentNodes the Kubernetes node names floci-oci runs as k3s agents of this cluster
     */
    void reconcile(StoredOkeCluster cluster, Set<String> agentNodes) {
        Optional<OkeLoadBalancerKube.Snapshot> read = kube.snapshot(cluster);
        if (read.isEmpty()) {
            return;
        }
        OkeLoadBalancerKube.Snapshot snapshot = read.get();
        Map<String, String> backendNodes = readyBackendNodes(snapshot.nodes(), agentNodes);
        Map<String, StoredLoadBalancer> stored = new LinkedHashMap<>();
        records.list(cluster).forEach(record -> stored.put(record.getServiceUid(), record));
        Set<String> present = new HashSet<>();
        for (JsonNode service : snapshot.services()) {
            OkeLoadBalancerSpec spec = OkeLoadBalancerSpec.from(service);
            present.add(spec.uid());
            StoredLoadBalancer record = stored.get(spec.uid());
            try {
                if (!spec.wantsLoadBalancer() || spec.deleting()) {
                    if (record != null || spec.hasCleanupFinalizer()) {
                        cleanUp(cluster, spec, record);
                    }
                } else {
                    ensure(cluster, spec, backendNodes, snapshot.endpointSlices());
                }
            } catch (OkeLoadBalancerAppliances.AddressInUseException e) {
                records.avoid(e.addresses());
                records.release(cluster, spec.uid());
                failed(cluster, spec, e.getMessage() + "; another address is picked on the next sync");
            } catch (RuntimeException e) {
                LOG.debugv(e, "Load balancer of Service {0}/{1} in cluster {2} not synced", spec.namespace(),
                        spec.name(), cluster.getId());
                failed(cluster, spec, e.getMessage());
            }
        }
        for (StoredLoadBalancer record : stored.values()) {
            if (!present.contains(record.getServiceUid())) {
                LOG.infov("Removing the load balancer of Service {0}/{1} in cluster {2}: the Service is gone",
                        record.getNamespace(), record.getName(), cluster.getId());
                appliances.remove(cluster, applianceName(record));
                records.release(cluster, record.getServiceUid());
                reportedFailures.remove(record.getServiceUid());
            }
        }
        Set<String> expected = new HashSet<>();
        records.list(cluster).forEach(record -> expected.add(applianceName(record)));
        for (String appliance : appliances.list(cluster)) {
            if (!expected.contains(appliance)) {
                LOG.infov("Removing load balancer appliance {0} of cluster {1}: no Service has it", appliance,
                        cluster.getId());
                appliances.remove(cluster, appliance);
            }
        }
    }

    private void ensure(StoredOkeCluster cluster, OkeLoadBalancerSpec spec, Map<String, String> backendNodes,
                        List<JsonNode> endpointSlices) {
        if (!spec.hasCleanupFinalizer() && !patchFinalizers(cluster, spec, true)) {
            return;
        }
        if (spec.error() != null) {
            failed(cluster, spec, spec.error());
            return;
        }
        OkeLoadBalancerRecords.Reservation reservation = records.reserve(cluster, spec, clock.instant());
        StoredLoadBalancer record = reservation.record();
        if (reservation.changed()) {
            record.setEnsured(false);
            kube.event(cluster, spec, NORMAL, "EnsuringLoadBalancer", "Ensuring load balancer");
        }
        List<String> backends = spec.localTrafficPolicy()
                ? localBackends(spec, backendNodes, endpointSlices)
                : List.copyOf(backendNodes.values());
        String name = applianceName(record);
        Map<String, Integer> hostPorts = appliances.ensure(cluster, new OkeLoadBalancerAppliances.Spec(name,
                spec.uid(), record.isInternal(), record.getPrivateIp(), record.getPublicIp(), forwardedPorts(spec),
                backends, spec.sourceRanges(), Map.copyOf(record.getHostPorts())));
        boolean backendsChanged = !backends.equals(record.getBackends());
        if (record.isEnsured() && backendsChanged) {
            if (backends.isEmpty()) {
                kube.event(cluster, spec, WARNING, "UnAvailableLoadBalancer",
                        "There are no available nodes for LoadBalancer");
            } else {
                kube.event(cluster, spec, NORMAL, "UpdatedLoadBalancer", "Updated load balancer with new hosts");
            }
        }
        if (reservation.changed() || backendsChanged || !hostPorts.equals(record.getHostPorts())
                || !name.equals(record.getApplianceName())) {
            record.setApplianceName(name);
            record.setBackends(backends);
            record.setHostPorts(hostPorts);
            records.save(cluster, record);
        }
        String annotation = hostPorts.isEmpty() ? null : OkeLoadBalancerAddresses.hostPortsAnnotation(hostPorts);
        if (!Objects.equals(annotation, spec.hostPortsAnnotation())) {
            kube.patchService(cluster, spec.namespace(), spec.name(), hostPortsPatch(annotation));
        }
        Instant provisionedAt = Instant.parse(record.getFirstSeen()).plusSeconds(provisioningDelaySeconds.getAsInt());
        if (clock.instant().isBefore(provisionedAt)) {
            return;
        }
        List<Map<String, String>> ingress = OkeLoadBalancerAddresses.ingress(record, spec.externalIpOnly(), spec.ipMode());
        // As upstream: the load balancer is ensured, reported, then its status written.
        if (!record.isEnsured()) {
            kube.event(cluster, spec, NORMAL, "EnsuredLoadBalancer", "Ensured load balancer");
        }
        if (!sameIngress(spec.statusIngress(), ingress)
                && !kube.patchServiceStatus(cluster, spec.namespace(), spec.name(), statusPatch(ingress))) {
            return;
        }
        reportedFailures.remove(spec.uid());
        if (!record.isEnsured()) {
            record.setEnsured(true);
            records.save(cluster, record);
            LOG.infov("Load balancer of Service {0}/{1} in cluster {2}: {3}", spec.namespace(), spec.name(),
                    cluster.getId(), ingress);
        }
    }

    /**
     * Removes what the Service's load balancer holds; the finalizer goes last, so a Service is never
     * gone while its appliance still runs. A Service that stays (its type changed) also loses its status.
     */
    private void cleanUp(StoredOkeCluster cluster, OkeLoadBalancerSpec spec, StoredLoadBalancer record) {
        if (record != null) {
            kube.event(cluster, spec, NORMAL, "DeletingLoadBalancer", "Deleting load balancer");
            appliances.remove(cluster, applianceName(record));
            records.release(cluster, spec.uid());
        }
        if (!spec.deleting()) {
            if (spec.statusIngress().isArray() && !spec.statusIngress().isEmpty()) {
                kube.patchServiceStatus(cluster, spec.namespace(), spec.name(),
                        "{\"status\":{\"loadBalancer\":{\"ingress\":null}}}");
            }
            if (spec.hostPortsAnnotation() != null) {
                kube.patchService(cluster, spec.namespace(), spec.name(), hostPortsPatch(null));
            }
        }
        if (spec.hasCleanupFinalizer() && !patchFinalizers(cluster, spec, false)) {
            return;
        }
        reportedFailures.remove(spec.uid());
        kube.event(cluster, spec, NORMAL, "DeletedLoadBalancer", "Deleted load balancer");
    }

    private void failed(StoredOkeCluster cluster, OkeLoadBalancerSpec spec, String reason) {
        String message = "Error syncing load balancer: failed to ensure load balancer: " + reason;
        if (!message.equals(reportedFailures.put(spec.uid(), message))) {
            LOG.warnv("Service {0}/{1} in cluster {2}: {3}", spec.namespace(), spec.name(), cluster.getId(), message);
            kube.event(cluster, spec, WARNING, "SyncLoadBalancerFailed", message);
        }
    }

    /**
     * Adds or removes the cleanup finalizer, sending the whole list with the Service's
     * {@code resourceVersion}, so a finalizer another controller added since the read is never lost:
     * the API server refuses the patch and the next pass retries.
     */
    private boolean patchFinalizers(StoredOkeCluster cluster, OkeLoadBalancerSpec spec, boolean present) {
        List<String> finalizers = new ArrayList<>(spec.finalizers());
        finalizers.remove(OkeLoadBalancerSpec.CLEANUP_FINALIZER);
        if (present) {
            finalizers.add(OkeLoadBalancerSpec.CLEANUP_FINALIZER);
        }
        ObjectNode patch = JSON.createObjectNode();
        ObjectNode metadata = patch.putObject("metadata");
        if (spec.resourceVersion() != null) {
            metadata.put("resourceVersion", spec.resourceVersion());
        }
        ArrayNode list = metadata.putArray("finalizers");
        finalizers.forEach(list::add);
        return kube.patchService(cluster, spec.namespace(), spec.name(), write(patch));
    }

    private static String hostPortsPatch(String annotation) {
        ObjectNode patch = JSON.createObjectNode();
        ObjectNode annotations = patch.putObject("metadata").putObject("annotations");
        if (annotation == null) {
            annotations.putNull(OkeLoadBalancerSpec.HOST_PORTS_ANNOTATION);
        } else {
            annotations.put(OkeLoadBalancerSpec.HOST_PORTS_ANNOTATION, annotation);
        }
        return write(patch);
    }

    private static String statusPatch(List<Map<String, String>> ingress) {
        ObjectNode patch = JSON.createObjectNode();
        patch.putObject("status").putObject("loadBalancer").set("ingress", JSON.valueToTree(ingress));
        return write(patch);
    }

    /**
     * Whether the status already says what {@code desired} says. The API server fills {@code ipMode: VIP}
     * when the writer leaves it out, so a missing one reads as VIP on both sides.
     */
    static boolean sameIngress(JsonNode current, List<Map<String, String>> desired) {
        if (!current.isArray()) {
            return desired.isEmpty();
        }
        if (current.size() != desired.size()) {
            return false;
        }
        for (int i = 0; i < desired.size(); i++) {
            JsonNode entry = current.get(i);
            Map<String, String> wanted = desired.get(i);
            if (!wanted.get("ip").equals(entry.path("ip").asText(null)) || entry.has("hostname")
                    || !wanted.getOrDefault("ipMode", "VIP").equals(entry.path("ipMode").asText("VIP"))) {
                return false;
            }
        }
        return true;
    }

    /** The ports the appliance can forward: those with a NodePort, which {@code allocateLoadBalancerNodePorts: false} omits. */
    private static List<OkeLoadBalancerSpec.Port> forwardedPorts(OkeLoadBalancerSpec spec) {
        return spec.ports().stream().filter(port -> port.nodePort() > 0).toList();
    }

    /** Ready agent nodes that do not opt out, by name, each with its internal address. */
    private static Map<String, String> readyBackendNodes(List<JsonNode> nodes, Set<String> agentNodes) {
        Map<String, String> backends = new TreeMap<>();
        for (JsonNode node : nodes) {
            String name = node.path("metadata").path("name").asText();
            if (!agentNodes.contains(name) || node.path("metadata").hasNonNull("deletionTimestamp")
                    || node.path("metadata").path("labels").has(EXCLUDE_LABEL) || !isReady(node)) {
                continue;
            }
            String address = name;
            for (JsonNode entry : node.path("status").path("addresses")) {
                if ("InternalIP".equals(entry.path("type").asText())) {
                    address = entry.path("address").asText(name);
                }
            }
            backends.put(name, address);
        }
        return backends;
    }

    private static boolean isReady(JsonNode node) {
        for (JsonNode condition : node.path("status").path("conditions")) {
            if ("Ready".equals(condition.path("type").asText())) {
                return "True".equals(condition.path("status").asText());
            }
        }
        return false;
    }

    /** {@code externalTrafficPolicy: Local}: kube-proxy answers a NodePort only on nodes with a local ready endpoint. */
    private static List<String> localBackends(OkeLoadBalancerSpec spec, Map<String, String> backendNodes,
                                              List<JsonNode> endpointSlices) {
        Map<String, String> local = new TreeMap<>();
        for (JsonNode slice : endpointSlices) {
            if (!spec.namespace().equals(slice.path("metadata").path("namespace").asText())
                    || !spec.name().equals(slice.path("metadata").path("labels")
                            .path("kubernetes.io/service-name").asText())) {
                continue;
            }
            for (JsonNode endpoint : slice.path("endpoints")) {
                String node = endpoint.path("nodeName").asText(null);
                boolean ready = endpoint.path("conditions").path("ready").asBoolean(true);
                if (ready && node != null && backendNodes.containsKey(node)) {
                    local.put(node, backendNodes.get(node));
                }
            }
        }
        return List.copyOf(local.values());
    }

    private String applianceName(StoredLoadBalancer record) {
        return record.getApplianceName() != null ? record.getApplianceName() : appliances.nameFor(record.getServiceUid());
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write a JSON patch", e);
        }
    }
}
