package io.floci.oci.services.oke;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What OKE's cloud controller manager would build for one Service, read from the Service's JSON
 * (oracle/oci-cloud-controller-manager {@code load_balancer_spec.go} at 93969b5). A cloud
 * controller handles a Service only when its type is {@code LoadBalancer} and it has no
 * {@code loadBalancerClass} (kubernetes/cloud-provider {@code wantsLoadBalancer}). {@code error} is
 * the CCM's message for a Service it refuses; such a Service stays pending.
 */
record OkeLoadBalancerSpec(
        String uid,
        String namespace,
        String name,
        String resourceVersion,
        boolean wantsLoadBalancer,
        boolean deleting,
        List<String> finalizers,
        Kind kind,
        boolean internal,
        String requestedPublicIp,
        String requestedPrivateIp,
        boolean externalIpOnly,
        String ipMode,
        List<Port> ports,
        boolean localTrafficPolicy,
        List<String> sourceRanges,
        JsonNode statusIngress,
        String hostPortsAnnotation,
        String error) {

    enum Kind {
        LB("lb"),
        NLB("nlb");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    record Port(String name, String protocol, int port, int nodePort) {

        /** {@code TCP/80}: how a port is named in records and in the host-ports annotation. */
        String key() {
            return protocol + "/" + port;
        }
    }

    static final String CLEANUP_FINALIZER = "service.kubernetes.io/load-balancer-cleanup";
    static final String HOST_PORTS_ANNOTATION = "floci.io/load-balancer-host-ports";
    static final String TYPE = "oci.oraclecloud.com/load-balancer-type";
    static final String LB_INTERNAL = "service.beta.kubernetes.io/oci-load-balancer-internal";
    static final String LB_SHAPE = "service.beta.kubernetes.io/oci-load-balancer-shape";
    static final String LB_SHAPE_FLEX_MIN = "service.beta.kubernetes.io/oci-load-balancer-shape-flex-min";
    static final String LB_SHAPE_FLEX_MAX = "service.beta.kubernetes.io/oci-load-balancer-shape-flex-max";
    static final String INGRESS_IP_MODE = "oci.oraclecloud.com/ingress-ip-mode";
    static final String NLB_INTERNAL = "oci-network-load-balancer.oraclecloud.com/internal";
    static final String NLB_SECURITY_LIST_MODE = "oci-network-load-balancer.oraclecloud.com/security-list-management-mode";
    static final String NLB_EXTERNAL_IP_ONLY = "oci-network-load-balancer.oraclecloud.com/external-ip-only";
    static final String NLB_ASSIGNED_PRIVATE_IPV4 = "oci-network-load-balancer.oraclecloud.com/assigned-private-ipv4";
    private static final Set<String> SECURITY_LIST_MODES = Set.of("All", "Frontend", "None");
    private static final Pattern IPV4 = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV4_CIDR = Pattern.compile(IPV4.pattern() + "/(3[0-2]|[12]?\\d)");

    static OkeLoadBalancerSpec from(JsonNode service) {
        JsonNode metadata = service.path("metadata");
        JsonNode spec = service.path("spec");
        JsonNode annotations = metadata.path("annotations");
        Kind kind = "nlb".equalsIgnoreCase(annotations.path(TYPE).asText()) ? Kind.NLB : Kind.LB;
        boolean wants = "LoadBalancer".equals(spec.path("type").asText())
                && (spec.path("loadBalancerClass").isMissingNode() || spec.path("loadBalancerClass").isNull());
        List<String> finalizers = new ArrayList<>();
        metadata.path("finalizers").forEach(finalizer -> finalizers.add(finalizer.asText()));
        List<Port> ports = new ArrayList<>();
        for (JsonNode port : spec.path("ports")) {
            ports.add(new Port(port.path("name").asText(""), port.path("protocol").asText("TCP").toUpperCase(Locale.ROOT),
                    port.path("port").asInt(), port.path("nodePort").asInt(0)));
        }
        List<String> sourceRanges = new ArrayList<>();
        spec.path("loadBalancerSourceRanges").forEach(range -> sourceRanges.add(range.asText().trim()));

        Errors errors = new Errors();
        validateProtocols(errors, annotations, kind, ports);
        if (!"None".equals(spec.path("sessionAffinity").asText("None"))) {
            errors.add("OCI only supports SessionAffinity \"None\" currently");
        }
        boolean internal = errors.bool(annotations, kind == Kind.NLB ? NLB_INTERNAL : LB_INTERNAL);
        if (kind == Kind.LB && "flexible".equalsIgnoreCase(annotations.path(LB_SHAPE).asText())
                && (annotations.path(LB_SHAPE_FLEX_MIN).asText("").isEmpty()
                        || annotations.path(LB_SHAPE_FLEX_MAX).asText("").isEmpty())) {
            errors.add("error parsing service annotation: " + LB_SHAPE + "=flexible requires " + LB_SHAPE_FLEX_MIN
                    + " and " + LB_SHAPE_FLEX_MAX + " to be set");
        }
        String ipMode = ipMode(errors, annotations);
        String requestedPublicIp = loadBalancerIp(errors, spec, internal);
        String requestedPrivateIp = assignedPrivateIp(errors, annotations, kind);
        boolean externalIpOnly = kind == Kind.NLB && !internal && errors.bool(annotations, NLB_EXTERNAL_IP_ONLY);
        for (String range : sourceRanges) {
            if (!IPV4_CIDR.matcher(range).matches()) {
                errors.add("invalid loadBalancerSourceRanges entry \"" + range
                        + "\": floci-oci applies IPv4 CIDR blocks only");
            }
        }
        return new OkeLoadBalancerSpec(metadata.path("uid").asText(), metadata.path("namespace").asText(),
                metadata.path("name").asText(), metadata.path("resourceVersion").asText(null), wants,
                metadata.hasNonNull("deletionTimestamp"), List.copyOf(finalizers), kind, internal,
                requestedPublicIp, requestedPrivateIp, externalIpOnly, ipMode, List.copyOf(ports),
                "Local".equals(spec.path("externalTrafficPolicy").asText()), List.copyOf(sourceRanges),
                service.path("status").path("loadBalancer").path("ingress"),
                annotations.path(HOST_PORTS_ANNOTATION).asText(null), errors.first);
    }

    boolean hasCleanupFinalizer() {
        return finalizers.contains(CLEANUP_FINALIZER);
    }

    /** {@code validateProtocols}: UDP needs an NLB whose security list management mode is {@code None}. */
    private static void validateProtocols(Errors errors, JsonNode annotations, Kind kind, List<Port> ports) {
        String securityListMode = annotations.path(NLB_SECURITY_LIST_MODE).asText("None");
        if (kind == Kind.NLB && !SECURITY_LIST_MODES.contains(securityListMode)) {
            errors.add("invalid value: " + securityListMode + " provided for annotation: " + NLB_SECURITY_LIST_MODE);
        }
        for (Port port : ports) {
            if ("UDP".equals(port.protocol()) && kind == Kind.LB) {
                errors.add("OCI load balancers do not support UDP");
            } else if ("UDP".equals(port.protocol()) && !"None".equals(securityListMode)) {
                errors.add("Security list management mode can only be 'None' for UDP protocol");
            }
        }
    }

    private static String ipMode(Errors errors, JsonNode annotations) {
        if (!annotations.has(INGRESS_IP_MODE)) {
            return null;
        }
        return switch (annotations.path(INGRESS_IP_MODE).asText().toLowerCase(Locale.ROOT)) {
            case "proxy" -> "Proxy";
            case "vip" -> "VIP";
            default -> {
                errors.add("IpMode can only be set as Proxy or VIP");
                yield null;
            }
        };
    }

    /** {@code spec.loadBalancerIP}: a reserved public IP, which a private load balancer cannot take. */
    private static String loadBalancerIp(Errors errors, JsonNode spec, boolean internal) {
        String ip = spec.path("loadBalancerIP").asText("");
        if (ip.isEmpty()) {
            return null;
        }
        if (!IPV4.matcher(ip).matches()) {
            errors.add("invalid value \"" + ip + "\" provided for LoadBalancerIP");
            return null;
        }
        if (internal) {
            errors.add("invalid service: cannot create a private load balancer with Reserved IP");
            return null;
        }
        return ip;
    }

    private static String assignedPrivateIp(Errors errors, JsonNode annotations, Kind kind) {
        if (!annotations.has(NLB_ASSIGNED_PRIVATE_IPV4)) {
            return null;
        }
        if (kind != Kind.NLB) {
            errors.add("Private IP assignment via annoations " + NLB_ASSIGNED_PRIVATE_IPV4
                    + " & oci-network-load-balancer.oraclecloud.com/assigned-ipv6 is supported only in OCI Network"
                    + " Loadbalancer. Set " + TYPE + " to nlb");
            return null;
        }
        String ip = annotations.path(NLB_ASSIGNED_PRIVATE_IPV4).asText().trim();
        if (!IPV4.matcher(ip).matches()) {
            errors.add("invalid value: " + ip + " provided for annotation: " + NLB_ASSIGNED_PRIVATE_IPV4);
            return null;
        }
        return ip;
    }

    /** Keeps the first error, the one the CCM would have stopped at. */
    private static final class Errors {
        private String first;

        void add(String message) {
            if (first == null) {
                first = message;
            }
        }

        /** Go's {@code strconv.ParseBool}, which the CCM reads boolean annotations with; absent is false. */
        boolean bool(JsonNode annotations, String key) {
            if (!annotations.has(key)) {
                return false;
            }
            String value = annotations.path(key).asText();
            return switch (value) {
                case "1", "t", "T", "TRUE", "true", "True" -> true;
                case "0", "f", "F", "FALSE", "false", "False" -> false;
                default -> {
                    add("invalid value: " + value + " provided for annotation: " + key);
                    yield false;
                }
            };
        }
    }
}
