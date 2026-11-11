package io.floci.oci.services.oke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.oci.services.oke.model.StoredLoadBalancer;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The controller's behavior follows kubernetes/cloud-provider {@code controllers/service/controller.go}
 * ({@code syncLoadBalancerIfNeeded}: finalizer before the load balancer, events
 * {@code EnsuringLoadBalancer}, {@code EnsuredLoadBalancer}, {@code SyncLoadBalancerFailed},
 * {@code DeletingLoadBalancer}, {@code DeletedLoadBalancer}, {@code UpdatedLoadBalancer},
 * {@code UnAvailableLoadBalancer}), against fake Kubernetes and Docker ports.
 */
class OkeLoadBalancerControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private FakeKube kube;
    private FakeAppliances appliances;
    private FakeRecords records;
    private SteppingClock clock;
    private StoredOkeCluster cluster;
    private Set<String> agents;

    @BeforeEach
    void setUp() {
        kube = new FakeKube();
        appliances = new FakeAppliances();
        records = new FakeRecords();
        clock = new SteppingClock(NOW);
        cluster = new StoredOkeCluster();
        cluster.setId("ocid1.cluster.oc1.iad.lbcontroller");
        cluster.setNodeSubnet("10.0.10.0/24");
        cluster.setLoadBalancerMode("emulated");
        agents = new HashSet<>(Set.of("10.0.10.16", "10.0.10.17"));
        kube.node("10.0.10.16", true);
        kube.node("10.0.10.17", true);
    }

    @Test
    void aLoadBalancerServiceGetsTheFinalizerThenAPublicAddressAndItsEvents() {
        kube.service(OkeLoadBalancerSpecTest.service(Map.of()));

        controller(0).reconcile(cluster, agents);

        ObjectNode service = kube.service("uid-1");
        assertEquals(List.of("service.kubernetes.io/load-balancer-cleanup"), strings(service.at("/metadata/finalizers")));
        assertEquals("192.0.2.10", service.at("/status/loadBalancer/ingress/0/ip").asText());
        assertEquals(List.of("metadata", "metadata", "status"), kube.patchOrder("uid-1"),
                "the finalizer comes first, then the host-ports annotation, then the status");
        assertEquals(List.of("Normal/EnsuringLoadBalancer/Ensuring load balancer",
                "Normal/EnsuredLoadBalancer/Ensured load balancer"), kube.events);
        FakeAppliances.Appliance appliance = appliances.only();
        assertEquals("192.0.2.10", appliance.spec().publicIp());
        assertFalse(appliance.spec().internal());
        assertEquals(List.of("10.0.10.16", "10.0.10.17"), appliance.spec().backends());
        assertEquals("TCP/80=127.0.0.1:40080", service.at("/metadata/annotations")
                .path(OkeLoadBalancerSpec.HOST_PORTS_ANNOTATION).asText());
    }

    @Test
    void aSecondPassChangesNothing() {
        kube.service(OkeLoadBalancerSpecTest.service(Map.of()));
        OkeLoadBalancerController controller = controller(0);
        controller.reconcile(cluster, agents);
        int patches = kube.patches.size();
        int events = kube.events.size();

        controller.reconcile(cluster, agents);

        assertEquals(patches, kube.patches.size());
        assertEquals(events, kube.events.size());
        assertEquals(1, appliances.created);
    }

    @Test
    void anInternalLoadBalancerTakesAnAddressFromTheTopOfTheNodeSubnet() {
        kube.service(OkeLoadBalancerSpecTest.service(Map.of(
                "service.beta.kubernetes.io/oci-load-balancer-internal", "true")));

        controller(0).reconcile(cluster, agents);

        assertEquals("10.0.10.254", kube.service("uid-1").at("/status/loadBalancer/ingress/0/ip").asText());
        assertTrue(appliances.only().spec().internal());
        assertEquals("10.0.10.254", appliances.only().spec().privateIp());
    }

    @Test
    void servicesOfAnotherClassOrTypeAreLeftAlone() {
        ObjectNode classed = OkeLoadBalancerSpecTest.service(Map.of());
        ((ObjectNode) classed.get("spec")).put("loadBalancerClass", "example.com/other");
        kube.service(classed);

        controller(0).reconcile(cluster, agents);

        assertTrue(kube.patches.isEmpty());
        assertTrue(kube.events.isEmpty());
        assertEquals(0, appliances.created);
    }

    @Test
    void anInvalidServiceStaysPendingWithOneSyncLoadBalancerFailedEvent() {
        kube.service(OkeLoadBalancerSpecTest.withPort(OkeLoadBalancerSpecTest.service(Map.of()), "dns", "UDP", 53, 30053));
        OkeLoadBalancerController controller = controller(0);

        controller.reconcile(cluster, agents);
        controller.reconcile(cluster, agents);

        ObjectNode service = kube.service("uid-1");
        assertTrue(service.at("/status/loadBalancer/ingress").isMissingNode());
        assertEquals(List.of("service.kubernetes.io/load-balancer-cleanup"), strings(service.at("/metadata/finalizers")),
                "the CCM adds its finalizer before it tries");
        assertEquals(List.of("Warning/SyncLoadBalancerFailed/Error syncing load balancer: failed to ensure load"
                + " balancer: OCI load balancers do not support UDP"), kube.events);
        assertEquals(0, appliances.created);
    }

    @Test
    void deletingTheServiceRemovesTheApplianceReleasesTheAddressAndDropsTheFinalizer() {
        kube.service(OkeLoadBalancerSpecTest.service(Map.of()));
        OkeLoadBalancerController controller = controller(0);
        controller.reconcile(cluster, agents);
        ObjectNode service = kube.service("uid-1");
        ((ArrayNode) service.with("metadata").withArray("finalizers")).add("example.com/keep");
        ((ObjectNode) service.get("metadata")).put("deletionTimestamp", "2026-11-11T10:01:00Z");
        kube.events.clear();

        controller.reconcile(cluster, agents);

        assertEquals(List.of("example.com/keep"), strings(service.at("/metadata/finalizers")));
        assertTrue(appliances.byName.isEmpty());
        assertTrue(records.list(cluster).isEmpty());
        assertEquals(List.of("Normal/DeletingLoadBalancer/Deleting load balancer",
                "Normal/DeletedLoadBalancer/Deleted load balancer"), kube.events);
    }

    @Test
    void turningTheServiceIntoAClusterIpCleansUpAndClearsItsStatus() {
        kube.service(OkeLoadBalancerSpecTest.service(Map.of()));
        OkeLoadBalancerController controller = controller(0);
        controller.reconcile(cluster, agents);
        ObjectNode service = kube.service("uid-1");
        ((ObjectNode) service.get("spec")).put("type", "ClusterIP");

        controller.reconcile(cluster, agents);

        assertTrue(strings(service.at("/metadata/finalizers")).isEmpty());
        assertTrue(service.at("/status/loadBalancer/ingress").isMissingNode()
                || service.at("/status/loadBalancer/ingress").isNull());
        assertTrue(service.at("/metadata/annotations").path(OkeLoadBalancerSpec.HOST_PORTS_ANNOTATION).isMissingNode());
        assertTrue(appliances.byName.isEmpty());
        assertTrue(records.list(cluster).isEmpty());
    }

    @Test
    void addressesAndAppliancesSurviveARestart() {
        kube.service(OkeLoadBalancerSpecTest.service(Map.of()));
        ObjectNode second = OkeLoadBalancerSpecTest.service(Map.of());
        ((ObjectNode) second.get("metadata")).put("uid", "uid-2").put("name", "api");
        kube.service(second);
        controller(0).reconcile(cluster, agents);
        String first = kube.service("uid-1").at("/status/loadBalancer/ingress/0/ip").asText();
        String other = kube.service("uid-2").at("/status/loadBalancer/ingress/0/ip").asText();
        int patches = kube.patches.size();
        kube.events.clear();

        controller(0).reconcile(cluster, agents);

        assertEquals(first, kube.service("uid-1").at("/status/loadBalancer/ingress/0/ip").asText());
        assertEquals(other, kube.service("uid-2").at("/status/loadBalancer/ingress/0/ip").asText());
        assertEquals(patches, kube.patches.size());
        assertTrue(kube.events.isEmpty());
        assertEquals(2, appliances.created, "an appliance with the same spec is adopted");
    }

    @Test
    void theProvisioningDelayHoldsTheStatusBack() {
        kube.service(OkeLoadBalancerSpecTest.service(Map.of()));
        OkeLoadBalancerController controller = controller(60);

        controller.reconcile(cluster, agents);
        assertTrue(kube.service("uid-1").at("/status/loadBalancer/ingress").isMissingNode());
        assertEquals(List.of("Normal/EnsuringLoadBalancer/Ensuring load balancer"), kube.events);

        clock.advance(Duration.ofSeconds(61));
        controller.reconcile(cluster, agents);
        assertEquals("192.0.2.10", kube.service("uid-1").at("/status/loadBalancer/ingress/0/ip").asText());
    }

    @Test
    void aNodeChangeUpdatesTheBackendsAndSaysSo() {
        kube.service(OkeLoadBalancerSpecTest.service(Map.of()));
        OkeLoadBalancerController controller = controller(0);
        controller.reconcile(cluster, agents);
        kube.events.clear();

        kube.node("10.0.10.17", false);
        controller.reconcile(cluster, agents);
        assertEquals(List.of("10.0.10.16"), appliances.only().spec().backends());
        assertEquals(List.of("Normal/UpdatedLoadBalancer/Updated load balancer with new hosts"), kube.events);

        kube.node("10.0.10.16", false);
        controller.reconcile(cluster, agents);
        assertEquals(List.of(), appliances.only().spec().backends());
        assertEquals("Warning/UnAvailableLoadBalancer/There are no available nodes for LoadBalancer",
                kube.events.getLast());
    }

    @Test
    void onlyNodesTheEmulatorRunsAndDoesNotExcludeAreBackends() {
        kube.node("10.0.10.99", true);
        kube.node("10.0.10.18", true);
        agents.add("10.0.10.18");
        kube.label("10.0.10.18", "node.kubernetes.io/exclude-from-external-load-balancers", "true");
        kube.service(OkeLoadBalancerSpecTest.service(Map.of()));

        controller(0).reconcile(cluster, agents);

        assertEquals(List.of("10.0.10.16", "10.0.10.17"), appliances.only().spec().backends());
    }

    @Test
    void aLocalTrafficPolicySendsTrafficOnlyToNodesWithReadyEndpoints() {
        ObjectNode service = OkeLoadBalancerSpecTest.service(Map.of());
        ((ObjectNode) service.get("spec")).put("externalTrafficPolicy", "Local");
        kube.service(service);
        kube.endpointSlice("web", "10.0.10.17", true);
        kube.endpointSlice("web", "10.0.10.16", false);

        controller(0).reconcile(cluster, agents);

        assertEquals(List.of("10.0.10.17"), appliances.only().spec().backends());
    }

    @Test
    void aRecordWhoseServiceIsGoneAndAnUnknownApplianceAreRemoved() {
        kube.service(OkeLoadBalancerSpecTest.service(Map.of()));
        OkeLoadBalancerController controller = controller(0);
        controller.reconcile(cluster, agents);
        kube.services.clear();
        appliances.byName.put("floci-oci-oke-lb-stray", new FakeAppliances.Appliance(null, Map.of()));

        controller.reconcile(cluster, agents);

        assertTrue(records.list(cluster).isEmpty());
        assertTrue(appliances.byName.isEmpty());
    }

    @Test
    void anUnreadableApiChangesNothing() {
        kube.service(OkeLoadBalancerSpecTest.service(Map.of()));
        controller(0).reconcile(cluster, agents);
        kube.readable = false;

        controller(0).reconcile(cluster, agents);

        assertEquals(1, records.list(cluster).size());
        assertEquals(1, appliances.byName.size());
    }

    @Test
    void anAddressThatCannotBeHadIsASyncFailure() {
        ObjectNode service = OkeLoadBalancerSpecTest.service(Map.of());
        ((ObjectNode) service.get("spec")).put("loadBalancerIP", "198.51.100.7");
        kube.service(service);

        controller(0).reconcile(cluster, agents);

        assertTrue(kube.service("uid-1").at("/status/loadBalancer/ingress").isMissingNode());
        assertEquals("Warning/SyncLoadBalancerFailed/Error syncing load balancer: failed to ensure load balancer:"
                + " loadBalancerIP 198.51.100.7 is not a free address of floci-oci's public load balancer range"
                + " 192.0.2.0/24", kube.events.getLast());
    }

    private OkeLoadBalancerController controller(int delaySeconds) {
        return new OkeLoadBalancerController(kube, appliances, records, clock, () -> delaySeconds);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static final class SteppingClock extends Clock {
        private Instant now;

        SteppingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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

    /** A Kubernetes API holding Service, Node and EndpointSlice objects, applying merge patches to them. */
    private static final class FakeKube implements OkeLoadBalancerKube {
        private final Map<String, ObjectNode> services = new LinkedHashMap<>();
        private final Map<String, ObjectNode> nodes = new LinkedHashMap<>();
        private final List<ObjectNode> slices = new ArrayList<>();
        private final List<String> patches = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private boolean readable = true;

        void service(ObjectNode service) {
            services.put(service.at("/metadata/uid").asText(), service);
        }

        ObjectNode service(String uid) {
            return services.get(uid);
        }

        void node(String name, boolean ready) {
            ObjectNode node = JSON.createObjectNode();
            node.with("metadata").put("name", name);
            node.with("metadata").putObject("labels");
            node.with("status").putArray("addresses").addObject().put("type", "InternalIP").put("address", name);
            node.with("status").putArray("conditions").addObject().put("type", "Ready")
                    .put("status", ready ? "True" : "False");
            nodes.put(name, node);
        }

        void label(String node, String key, String value) {
            nodes.get(node).with("metadata").with("labels").put(key, value);
        }

        void endpointSlice(String service, String nodeName, boolean ready) {
            ObjectNode slice = JSON.createObjectNode();
            slice.with("metadata").put("namespace", "default").with("labels").put("kubernetes.io/service-name", service);
            ObjectNode endpoint = slice.putArray("endpoints").addObject();
            endpoint.put("nodeName", nodeName);
            endpoint.with("conditions").put("ready", ready);
            slices.add(slice);
        }

        List<String> patchOrder(String uid) {
            List<String> order = new ArrayList<>();
            for (String patch : patches) {
                if (patch.startsWith(uid + " ")) {
                    order.add(patch.substring(uid.length() + 1));
                }
            }
            return order;
        }

        @Override
        public Optional<Snapshot> snapshot(StoredOkeCluster cluster) {
            if (!readable) {
                return Optional.empty();
            }
            List<JsonNode> serviceCopies = new ArrayList<>();
            services.values().forEach(service -> serviceCopies.add(service.deepCopy()));
            return Optional.of(new Snapshot(serviceCopies, List.copyOf(nodes.values()), List.copyOf(slices)));
        }

        @Override
        public boolean patchService(StoredOkeCluster cluster, String namespace, String name, String mergePatch) {
            ObjectNode service = byName(name);
            patches.add(service.at("/metadata/uid").asText() + " metadata");
            merge(service, read(mergePatch));
            return true;
        }

        @Override
        public boolean patchServiceStatus(StoredOkeCluster cluster, String namespace, String name, String mergePatch) {
            ObjectNode service = byName(name);
            patches.add(service.at("/metadata/uid").asText() + " status");
            merge(service, read(mergePatch));
            return true;
        }

        @Override
        public void event(StoredOkeCluster cluster, OkeLoadBalancerSpec service, String type, String reason,
                          String message) {
            events.add(type + "/" + reason + "/" + message);
        }

        private ObjectNode byName(String name) {
            return services.values().stream().filter(s -> name.equals(s.at("/metadata/name").asText()))
                    .findFirst().orElseThrow();
        }

        private static void merge(ObjectNode target, JsonNode patch) {
            Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getValue().isNull()) {
                    target.remove(field.getKey());
                } else if (field.getValue().isObject() && target.path(field.getKey()).isObject()) {
                    merge((ObjectNode) target.get(field.getKey()), field.getValue());
                } else {
                    target.set(field.getKey(), field.getValue().deepCopy());
                }
            }
        }

        private static JsonNode read(String json) {
            try {
                return JSON.readTree(json);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static final class FakeAppliances implements OkeLoadBalancerAppliances {
        record Appliance(OkeLoadBalancerAppliances.Spec spec, Map<String, Integer> hostPorts) {
        }

        private final Map<String, Appliance> byName = new LinkedHashMap<>();
        private int created;
        private String taken;

        Appliance only() {
            assertEquals(1, byName.size(), byName::toString);
            return byName.values().iterator().next();
        }

        @Override
        public String nameFor(String serviceUid) {
            return "floci-oci-oke-lb-" + serviceUid;
        }

        @Override
        public Map<String, Integer> ensure(StoredOkeCluster cluster, Spec spec) {
            if (spec.publicIp() != null && spec.publicIp().equals(taken)) {
                throw new AddressInUseException(List.of(spec.publicIp()), new RuntimeException("Address already in use"));
            }
            Appliance existing = byName.get(spec.name());
            if (existing != null && spec.sameAppliance(existing.spec())) {
                return existing.hostPorts();
            }
            created++;
            Map<String, Integer> hostPorts = new LinkedHashMap<>();
            for (OkeLoadBalancerSpec.Port port : spec.ports()) {
                hostPorts.put(port.key(), spec.preferredHostPorts().getOrDefault(port.key(), 40000 + port.port()));
            }
            byName.put(spec.name(), new Appliance(spec, hostPorts));
            return hostPorts;
        }

        @Override
        public void remove(StoredOkeCluster cluster, String name) {
            byName.remove(name);
        }

        @Override
        public Set<String> list(StoredOkeCluster cluster) {
            return Set.copyOf(byName.keySet());
        }
    }

    /** Records kept in memory, with the address rules of {@link OkeLoadBalancerAddresses}. */
    private static final class FakeRecords implements OkeLoadBalancerRecords {
        private final Map<String, StoredLoadBalancer> records = new LinkedHashMap<>();
        private final Set<String> avoided = new HashSet<>();

        @Override
        public void avoid(Collection<String> addresses) {
            avoided.addAll(addresses);
        }

        @Override
        public List<StoredLoadBalancer> list(StoredOkeCluster cluster) {
            return List.copyOf(records.values());
        }

        @Override
        public Reservation reserve(StoredOkeCluster cluster, OkeLoadBalancerSpec spec, Instant now) {
            StoredLoadBalancer existing = records.get(spec.uid());
            if (existing != null) {
                return new Reservation(existing, false);
            }
            if (spec.requestedPublicIp() != null
                    && !OkeLoadBalancerAddresses.isAllocatablePublic(spec.requestedPublicIp(), "192.0.2.0/24")) {
                throw new IllegalStateException("loadBalancerIP " + spec.requestedPublicIp()
                        + " is not a free address of floci-oci's public load balancer range 192.0.2.0/24");
            }
            Set<String> used = new HashSet<>(avoided);
            records.values().forEach(r -> {
                used.add(r.getPublicIp());
                used.add(r.getPrivateIp());
            });
            StoredLoadBalancer record = new StoredLoadBalancer();
            record.setClusterId(cluster.getId());
            record.setServiceUid(spec.uid());
            record.setNamespace(spec.namespace());
            record.setName(spec.name());
            record.setKind(spec.kind().value());
            record.setInternal(spec.internal());
            if (spec.internal()) {
                record.setPrivateIp(OkeLoadBalancerAddresses.nextInternal(cluster.getNodeSubnet(), used));
            } else {
                record.setPublicIp(OkeLoadBalancerAddresses.nextPublic("192.0.2.0/24", used));
            }
            record.setFirstSeen(now.toString());
            records.put(spec.uid(), record);
            return new Reservation(record, true);
        }

        @Override
        public void save(StoredOkeCluster cluster, StoredLoadBalancer record) {
            records.put(record.getServiceUid(), record);
        }

        @Override
        public void release(StoredOkeCluster cluster, String serviceUid) {
            records.remove(serviceUid);
        }
    }

    @Test
    void anAddressDockerSaysIsTakenIsAvoidedOnTheNextPass() {
        kube.service(OkeLoadBalancerSpecTest.service(Map.of()));
        appliances.taken = "192.0.2.10";
        OkeLoadBalancerController controller = controller(0);

        controller.reconcile(cluster, agents);
        assertTrue(kube.events.getLast().startsWith("Warning/SyncLoadBalancerFailed/"), kube.events::toString);
        assertTrue(records.list(cluster).isEmpty());

        controller.reconcile(cluster, agents);
        assertEquals("192.0.2.11", kube.service("uid-1").at("/status/loadBalancer/ingress/0/ip").asText());
    }

    @Test
    void theRecordKeepsTheHostPortsSoARecreatedApplianceAsksForThemAgain() {
        kube.service(OkeLoadBalancerSpecTest.service(Map.of()));
        OkeLoadBalancerController controller = controller(0);
        controller.reconcile(cluster, agents);

        kube.node("10.0.10.17", false);
        controller.reconcile(cluster, agents);

        assertEquals(Map.of("TCP/80", 40080), appliances.only().spec().preferredHostPorts());
        assertNull(records.list(cluster).getFirst().getHostPorts().get("UDP/53"));
    }
}
