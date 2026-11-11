package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;
import io.floci.oci.services.oke.model.StoredNode;
import io.floci.oci.services.oke.model.StoredNodePool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The labels, annotations and taints an OKE worker node carries. Sources, all cited on the docs page:
 * <ul>
 *   <li>Oracle's "Supported Labels for Different Usecases": {@code topology.kubernetes.io/zone} from the
 *       availability domain ({@code US-ASHBURN-AD-1}) and {@code oci.oraclecloud.com/fault-domain}.</li>
 *   <li>oracle/oci-cloud-controller-manager: the zone is the part of the availability domain after
 *       {@code :}, the region is the instance's region ({@code iad} and {@code phx} for the two
 *       original regions, the region name elsewhere), the provider ID is {@code oci://<instance OCID>},
 *       and the node gets the {@code oci.oraclecloud.com/compartment-id} annotation.</li>
 *   <li>Oracle's mixed node clusters announcement: managed nodes carry
 *       {@code oci.oraclecloud.com/node.info.managed=true} and the {@code node} role.</li>
 *   <li>A real node's labels (OKE 1.24, published by an Oracle engineer): {@code displayName},
 *       {@code hostname}, {@code internal_addr}, {@code name} (the node pool), {@code node.info/*}.
 *       Not documented by Oracle.</li>
 * </ul>
 */
final class OkeNodeLabels {

    static final String ROLE_LABEL = "node-role.kubernetes.io/node";
    static final String COMPARTMENT_ANNOTATION = "oci.oraclecloud.com/compartment-id";
    /** OKE GPU nodes "have a nvidia.com/gpu=present:NoSchedule taint" (Oracle's vLLM on OKE tutorial). */
    static final String GPU_TAINT = "nvidia.com/gpu=present:NoSchedule";
    static final String GPU_RESOURCE = "nvidia.com/gpu";

    /** The two original regions keep their short names in an instance's {@code region} (oci-go-sdk core.Instance). */
    private static final Map<String, String> SHORT_REGION_NAMES = Map.of(
            "us-ashburn-1", "iad",
            "us-phoenix-1", "phx");

    /** Labels in the kubernetes.io and k8s.io namespaces a kubelet may set on itself (NodeRestriction). */
    private static final Set<String> KUBELET_ALLOWED_LABELS = Set.of("kubernetes.io/hostname",
            "kubernetes.io/arch", "kubernetes.io/os", "beta.kubernetes.io/instance-type",
            "node.kubernetes.io/instance-type", "failure-domain.beta.kubernetes.io/region",
            "failure-domain.beta.kubernetes.io/zone", "topology.kubernetes.io/region",
            "topology.kubernetes.io/zone", "beta.kubernetes.io/arch", "beta.kubernetes.io/os");
    private static final List<String> KUBELET_ALLOWED_PREFIXES = List.of("kubelet.kubernetes.io/",
            "node.kubernetes.io/");

    private static final Pattern NAME = Pattern.compile("([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9]");
    private static final Pattern DNS_SUBDOMAIN = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*");
    private static final int MAX_NAME = 63;
    private static final int MAX_PREFIX = 253;

    private OkeNodeLabels() {
    }

    /** The node labels a k3s agent registers with ({@code --node-label}). */
    static Map<String, String> registrationLabels(StoredNodePool pool, StoredNode node, String region) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("node.kubernetes.io/instance-type", pool.getNodeShape());
        labels.put("beta.kubernetes.io/instance-type", pool.getNodeShape());
        String regionLabel = regionLabel(region);
        String zone = zone(node.getAvailabilityDomain());
        if (regionLabel != null) {
            labels.put("topology.kubernetes.io/region", regionLabel);
            labels.put("failure-domain.beta.kubernetes.io/region", regionLabel);
        }
        if (zone != null) {
            labels.put("topology.kubernetes.io/zone", zone);
            labels.put("failure-domain.beta.kubernetes.io/zone", zone);
        }
        labels.put("oci.oraclecloud.com/fault-domain", node.getFaultDomain());
        labels.put("oci.oraclecloud.com/node.info.managed", "true");
        labels.put("displayName", node.getName());
        labels.put("hostname", node.getName());
        labels.put("internal_addr", node.getPrivateIp());
        if (isValidValue(pool.getName())) {
            labels.put("name", pool.getName());
        }
        labels.put("node.info/kubeletVersion", OkeVersions.minorName(node.getKubernetesVersion()));
        compartmentLabels(pool.getCompartmentId(), labels);
        for (Map.Entry<String, String> label : initialLabels(pool).entrySet()) {
            if (kubeletMaySet(label.getKey())) {
                labels.put(label.getKey(), label.getValue());
            }
        }
        labels.values().removeIf(value -> value == null || !isValidValue(value));
        return labels;
    }

    /** The labels a kubelet may not set on itself, applied through the API once the node registers. */
    static Map<String, String> postRegistrationLabels(StoredNodePool pool) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(ROLE_LABEL, "");
        for (Map.Entry<String, String> label : initialLabels(pool).entrySet()) {
            if (!kubeletMaySet(label.getKey())) {
                labels.put(label.getKey(), label.getValue());
            }
        }
        return labels;
    }

    static Map<String, String> annotations(StoredNodePool pool) {
        return Map.of(COMPARTMENT_ANNOTATION, pool.getCompartmentId());
    }

    static String providerId(StoredNode node) {
        return "oci://" + node.getId();
    }

    /** {@code iad}/{@code phx} for the two original regions, the region name elsewhere. */
    static String regionLabel(String region) {
        return region == null ? null : SHORT_REGION_NAMES.getOrDefault(region, region);
    }

    /** The availability domain without its tenancy prefix: {@code Floc:US-ASHBURN-AD-1} is {@code US-ASHBURN-AD-1}. */
    static String zone(String availabilityDomain) {
        if (availabilityDomain == null) {
            return null;
        }
        return availabilityDomain.substring(availabilityDomain.lastIndexOf(':') + 1);
    }

    /**
     * {@code initialNodeLabels} must be valid Kubernetes labels, or the node could not register. OCI
     * does not document which error it returns for an invalid one, so 400 InvalidParameter is
     * floci-oci's choice.
     */
    static void validate(List<Map<String, Object>> labels) {
        if (labels == null) {
            return;
        }
        for (Map<String, Object> label : labels) {
            String key = String.valueOf(label.get("key"));
            String value = String.valueOf(label.get("value"));
            if (!isValidKey(key)) {
                throw OciException.invalidParameter("Invalid initialNodeLabels key: " + key);
            }
            if (!value.isEmpty() && !isValidValue(value)) {
                throw OciException.invalidParameter("Invalid initialNodeLabels value for " + key + ": " + value);
            }
        }
    }

    private static Map<String, String> initialLabels(StoredNodePool pool) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (pool.getInitialNodeLabels() != null) {
            for (Map<String, Object> label : pool.getInitialNodeLabels()) {
                labels.put(String.valueOf(label.get("key")), String.valueOf(label.get("value")));
            }
        }
        return labels;
    }

    /**
     * {@code node.info/compartment.id_prefix} and {@code id_suffix}: the compartment OCID split at
     * {@code ..}, when both halves are valid label values.
     */
    private static void compartmentLabels(String compartmentId, Map<String, String> labels) {
        if (compartmentId == null) {
            return;
        }
        int split = compartmentId.indexOf("..");
        if (split > 0 && isValidValue(compartmentId.substring(0, split))
                && isValidValue(compartmentId.substring(split + 2))) {
            labels.put("node.info/compartment.id_prefix", compartmentId.substring(0, split));
            labels.put("node.info/compartment.id_suffix", compartmentId.substring(split + 2));
        }
    }

    static boolean kubeletMaySet(String key) {
        int slash = key.indexOf('/');
        if (slash < 0) {
            return true;
        }
        String prefix = key.substring(0, slash);
        boolean restricted = prefix.equals("kubernetes.io") || prefix.endsWith(".kubernetes.io")
                || prefix.equals("k8s.io") || prefix.endsWith(".k8s.io");
        return !restricted || KUBELET_ALLOWED_LABELS.contains(key)
                || KUBELET_ALLOWED_PREFIXES.stream().anyMatch(key::startsWith);
    }

    static boolean isValidKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        int slash = key.indexOf('/');
        String name = slash < 0 ? key : key.substring(slash + 1);
        if (slash >= 0) {
            String prefix = key.substring(0, slash);
            if (prefix.isEmpty() || prefix.length() > MAX_PREFIX || !DNS_SUBDOMAIN.matcher(prefix).matches()) {
                return false;
            }
        }
        return name.length() <= MAX_NAME && NAME.matcher(name).matches();
    }

    static boolean isValidValue(String value) {
        return value != null && (value.isEmpty() || (value.length() <= MAX_NAME && NAME.matcher(value).matches()));
    }
}
