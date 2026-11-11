package io.floci.oci.services.oke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One case per rule of OKE's cloud controller manager, oracle/oci-cloud-controller-manager at
 * 93969b5: {@code load_balancer_spec.go} (annotations, {@code isInternalLB}, {@code getLBShape},
 * {@code getIngressIpMode}, {@code getLoadBalancerIP}, {@code getAssignedPrivateIP},
 * {@code isSkipPrivateIP}) and {@code load_balancer_util.go validateProtocols}; which Services a
 * cloud controller handles from kubernetes/cloud-provider {@code controller.go wantsLoadBalancer}.
 */
class OkeLoadBalancerSpecTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aPlainLoadBalancerServiceIsAPublicOciLoadBalancer() {
        OkeLoadBalancerSpec spec = OkeLoadBalancerSpec.from(service(Map.of()));

        assertTrue(spec.wantsLoadBalancer());
        assertEquals(OkeLoadBalancerSpec.Kind.LB, spec.kind());
        assertFalse(spec.internal());
        assertNull(spec.error());
        assertEquals("uid-1", spec.uid());
        assertEquals("default", spec.namespace());
        assertEquals("web", spec.name());
        assertEquals(List.of(new OkeLoadBalancerSpec.Port("http", "TCP", 80, 30080)), spec.ports());
    }

    @Test
    void aLoadBalancerClassOrAnotherTypeIsNotOkes() {
        ObjectNode classed = service(Map.of());
        ((ObjectNode) classed.get("spec")).put("loadBalancerClass", "example.com/other");
        ObjectNode clusterIp = service(Map.of());
        ((ObjectNode) clusterIp.get("spec")).put("type", "ClusterIP");

        assertFalse(OkeLoadBalancerSpec.from(classed).wantsLoadBalancer());
        assertFalse(OkeLoadBalancerSpec.from(clusterIp).wantsLoadBalancer());
    }

    @Test
    void theInternalAnnotationDependsOnTheType() {
        assertTrue(OkeLoadBalancerSpec.from(service(Map.of(
                "service.beta.kubernetes.io/oci-load-balancer-internal", "true"))).internal());
        assertTrue(OkeLoadBalancerSpec.from(service(Map.of(
                "oci.oraclecloud.com/load-balancer-type", "nlb",
                "oci-network-load-balancer.oraclecloud.com/internal", "true"))).internal());
        OkeLoadBalancerSpec nlbWithLbAnnotation = OkeLoadBalancerSpec.from(service(Map.of(
                "oci.oraclecloud.com/load-balancer-type", "nlb",
                "service.beta.kubernetes.io/oci-load-balancer-internal", "true")));
        assertEquals(OkeLoadBalancerSpec.Kind.NLB, nlbWithLbAnnotation.kind());
        assertFalse(nlbWithLbAnnotation.internal(), "an NLB reads its own internal annotation");
        assertEquals("invalid value: yes provided for annotation: service.beta.kubernetes.io/oci-load-balancer-internal",
                OkeLoadBalancerSpec.from(service(Map.of(
                        "service.beta.kubernetes.io/oci-load-balancer-internal", "yes"))).error());
    }

    @Test
    void anUnknownTypeIsAnOciLoadBalancer() {
        assertEquals(OkeLoadBalancerSpec.Kind.LB, OkeLoadBalancerSpec.from(service(Map.of(
                "oci.oraclecloud.com/load-balancer-type", "alb"))).kind());
        assertEquals(OkeLoadBalancerSpec.Kind.NLB, OkeLoadBalancerSpec.from(service(Map.of(
                "oci.oraclecloud.com/load-balancer-type", "NLB"))).kind());
    }

    @Test
    void udpNeedsANetworkLoadBalancerWithoutSecurityListManagement() {
        assertEquals("OCI load balancers do not support UDP",
                OkeLoadBalancerSpec.from(withPort(service(Map.of()), "dns", "UDP", 53, 30053)).error());
        assertNull(OkeLoadBalancerSpec.from(withPort(service(Map.of(
                "oci.oraclecloud.com/load-balancer-type", "nlb")), "dns", "UDP", 53, 30053)).error());
        assertEquals("Security list management mode can only be 'None' for UDP protocol",
                OkeLoadBalancerSpec.from(withPort(service(Map.of(
                        "oci.oraclecloud.com/load-balancer-type", "nlb",
                        "oci-network-load-balancer.oraclecloud.com/security-list-management-mode", "All")),
                        "dns", "UDP", 53, 30053)).error());
    }

    @Test
    void onlySessionAffinityNoneIsSupported() {
        ObjectNode sticky = service(Map.of());
        ((ObjectNode) sticky.get("spec")).put("sessionAffinity", "ClientIP");

        assertEquals("OCI only supports SessionAffinity \"None\" currently", OkeLoadBalancerSpec.from(sticky).error());
    }

    @Test
    void aFlexibleShapeNeedsItsMinimumAndMaximum() {
        assertEquals("error parsing service annotation: service.beta.kubernetes.io/oci-load-balancer-shape=flexible"
                        + " requires service.beta.kubernetes.io/oci-load-balancer-shape-flex-min and"
                        + " service.beta.kubernetes.io/oci-load-balancer-shape-flex-max to be set",
                OkeLoadBalancerSpec.from(service(Map.of(
                        "service.beta.kubernetes.io/oci-load-balancer-shape", "Flexible"))).error());
        assertNull(OkeLoadBalancerSpec.from(service(Map.of(
                "service.beta.kubernetes.io/oci-load-balancer-shape", "flexible",
                "service.beta.kubernetes.io/oci-load-balancer-shape-flex-min", "10",
                "service.beta.kubernetes.io/oci-load-balancer-shape-flex-max", "100"))).error());
        assertNull(OkeLoadBalancerSpec.from(service(Map.of(
                "oci.oraclecloud.com/load-balancer-type", "nlb",
                "service.beta.kubernetes.io/oci-load-balancer-shape", "flexible"))).error(),
                "a network load balancer has no shape");
    }

    @Test
    void theIngressIpModeAnnotationSetsIpMode() {
        assertEquals("Proxy", OkeLoadBalancerSpec.from(service(Map.of(
                "oci.oraclecloud.com/ingress-ip-mode", "proxy"))).ipMode());
        assertEquals("VIP", OkeLoadBalancerSpec.from(service(Map.of(
                "oci.oraclecloud.com/ingress-ip-mode", "VIP"))).ipMode());
        assertNull(OkeLoadBalancerSpec.from(service(Map.of())).ipMode());
        assertEquals("IpMode can only be set as Proxy or VIP", OkeLoadBalancerSpec.from(service(Map.of(
                "oci.oraclecloud.com/ingress-ip-mode", "direct"))).error());
    }

    @Test
    void aReservedIpIsForPublicLoadBalancersOnly() {
        ObjectNode reserved = service(Map.of());
        ((ObjectNode) reserved.get("spec")).put("loadBalancerIP", "192.0.2.50");
        assertEquals("192.0.2.50", OkeLoadBalancerSpec.from(reserved).requestedPublicIp());

        ObjectNode privateReserved = service(Map.of("service.beta.kubernetes.io/oci-load-balancer-internal", "true"));
        ((ObjectNode) privateReserved.get("spec")).put("loadBalancerIP", "10.0.10.200");
        assertEquals("invalid service: cannot create a private load balancer with Reserved IP",
                OkeLoadBalancerSpec.from(privateReserved).error());

        ObjectNode garbage = service(Map.of());
        ((ObjectNode) garbage.get("spec")).put("loadBalancerIP", "not-an-ip");
        assertEquals("invalid value \"not-an-ip\" provided for LoadBalancerIP", OkeLoadBalancerSpec.from(garbage).error());
    }

    @Test
    void anAssignedPrivateIpIsForNetworkLoadBalancersOnly() {
        assertEquals("10.0.10.200", OkeLoadBalancerSpec.from(service(Map.of(
                "oci.oraclecloud.com/load-balancer-type", "nlb",
                "oci-network-load-balancer.oraclecloud.com/assigned-private-ipv4", "10.0.10.200")))
                .requestedPrivateIp());
        assertTrue(OkeLoadBalancerSpec.from(service(Map.of(
                "oci-network-load-balancer.oraclecloud.com/assigned-private-ipv4", "10.0.10.200"))).error()
                .startsWith("Private IP assignment via annoations"));
    }

    @Test
    void externalIpOnlyAppliesToPublicNetworkLoadBalancers() {
        assertTrue(OkeLoadBalancerSpec.from(service(Map.of(
                "oci.oraclecloud.com/load-balancer-type", "nlb",
                "oci-network-load-balancer.oraclecloud.com/external-ip-only", "true"))).externalIpOnly());
        assertFalse(OkeLoadBalancerSpec.from(service(Map.of(
                "oci.oraclecloud.com/load-balancer-type", "nlb",
                "oci-network-load-balancer.oraclecloud.com/internal", "true",
                "oci-network-load-balancer.oraclecloud.com/external-ip-only", "true"))).externalIpOnly());
        assertFalse(OkeLoadBalancerSpec.from(service(Map.of(
                "oci-network-load-balancer.oraclecloud.com/external-ip-only", "true"))).externalIpOnly());
    }

    @Test
    void deletionFinalizersTrafficPolicyAndSourceRangesAreRead() {
        ObjectNode service = service(Map.of());
        ObjectNode metadata = (ObjectNode) service.get("metadata");
        metadata.put("deletionTimestamp", "2026-11-11T00:00:00Z");
        metadata.putArray("finalizers").add("service.kubernetes.io/load-balancer-cleanup").add("example.com/other");
        ((ObjectNode) service.get("spec")).put("externalTrafficPolicy", "Local");
        ((ObjectNode) service.get("spec")).putArray("loadBalancerSourceRanges").add("203.0.113.0/24");

        OkeLoadBalancerSpec spec = OkeLoadBalancerSpec.from(service);

        assertTrue(spec.deleting());
        assertTrue(spec.hasCleanupFinalizer());
        assertEquals(List.of("service.kubernetes.io/load-balancer-cleanup", "example.com/other"), spec.finalizers());
        assertTrue(spec.localTrafficPolicy());
        assertEquals(List.of("203.0.113.0/24"), spec.sourceRanges());
    }

    @Test
    void aSourceRangeThatIsNotAnIpv4CidrIsAnError() {
        ObjectNode service = service(Map.of());
        ((ObjectNode) service.get("spec")).putArray("loadBalancerSourceRanges").add("everyone; rm -rf /");

        assertTrue(OkeLoadBalancerSpec.from(service).error().startsWith("invalid loadBalancerSourceRanges"));
    }

    static ObjectNode service(Map<String, String> annotations) {
        ObjectNode service = JSON.createObjectNode();
        service.put("kind", "Service");
        ObjectNode metadata = service.putObject("metadata");
        metadata.put("name", "web");
        metadata.put("namespace", "default");
        metadata.put("uid", "uid-1");
        metadata.put("resourceVersion", "100");
        ObjectNode annotationNode = metadata.putObject("annotations");
        annotations.forEach(annotationNode::put);
        ObjectNode spec = service.putObject("spec");
        spec.put("type", "LoadBalancer");
        spec.put("sessionAffinity", "None");
        spec.put("externalTrafficPolicy", "Cluster");
        ArrayNode ports = spec.putArray("ports");
        ObjectNode port = ports.addObject();
        port.put("name", "http");
        port.put("protocol", "TCP");
        port.put("port", 80);
        port.put("nodePort", 30080);
        return service;
    }

    static ObjectNode withPort(ObjectNode service, String name, String protocol, int port, int nodePort) {
        ArrayNode ports = (ArrayNode) service.get("spec").get("ports");
        ports.removeAll();
        ObjectNode entry = ports.addObject();
        entry.put("name", name);
        entry.put("protocol", protocol);
        entry.put("port", port);
        entry.put("nodePort", nodePort);
        return service;
    }

    static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
