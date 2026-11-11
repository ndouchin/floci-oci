package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;
import io.floci.oci.services.oke.model.StoredNode;
import io.floci.oci.services.oke.model.StoredNodePool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * How many nodes a node pool has and where each one sits, from the fields of CreateNodePoolDetails
 * and UpdateNodePoolDetails (oci-go-sdk {@code containerengine}): {@code nodeConfigDetails.size} over
 * its {@code placementConfigs} when set, otherwise the deprecated {@code quantityPerSubnet} nodes in
 * each of {@code subnetIds}.
 */
final class OkeNodePools {

    /** "Each availability domain has three fault domains" (Oracle's supported labels reference). */
    static final int FAULT_DOMAINS_PER_AD = 3;
    /** Node addresses start here, leaving the low addresses to Docker's gateway and the control plane. */
    static final int FIRST_NODE_HOST = 16;
    static final int LAST_NODE_HOST = 254;
    static final String DEFAULT_NODE_SUBNET = "10.0.10.0/24";
    /** Oracle's example names carry ten characters of each OCID: {@code oke-cywiqripuyg-nsgagklgnst-st2qczvnmba-0}. */
    private static final int NAME_PART_LENGTH = 10;

    /** CreateNodePoolNodeConfigDetails and UpdateNodePoolNodeConfigDetails. */
    private static final Set<String> NODE_CONFIG_DETAILS = Set.of("size", "placementConfigs", "nsgIds", "kmsKeyId",
            "isPvEncryptionInTransitEnabled", "computeClusterId", "freeformTags", "definedTags",
            "nodePoolPodNetworkOptionDetails");
    /** NodePoolPlacementConfigDetails. */
    private static final Set<String> PLACEMENT_CONFIG = Set.of("availabilityDomain", "subnetId",
            "capacityReservationId", "hostGroupId", "preemptibleNodeConfig", "faultDomains");
    /** CreateNodeShapeConfigDetails, UpdateNodeShapeConfigDetails and NodeShapeConfig. */
    private static final Set<String> SHAPE_CONFIG = Set.of("ocpus", "memoryInGBs");
    /** NodePoolCyclingDetails. */
    private static final Set<String> CYCLING_DETAILS = Set.of("maximumUnavailable", "maximumSurge",
            "isNodeCyclingEnabled", "cycleModes");
    /** NodeEvictionNodePoolSettings. */
    private static final Set<String> EVICTION_SETTINGS = Set.of("evictionGraceDuration",
            "isForceDeleteAfterGraceDuration", "isForceActionAfterGraceDuration");

    /** Where one node goes. */
    record Placement(String availabilityDomain, String subnetId, List<String> faultDomains) {}

    private OkeNodePools() {
    }

    /** The number of nodes the pool asks for. */
    static int requestedCount(StoredNodePool pool) {
        Map<String, Object> nodeConfig = pool.getNodeConfigDetails();
        if (nodeConfig != null && nodeConfig.get("size") instanceof Number size) {
            return size.intValue();
        }
        List<String> subnets = pool.getSubnetIds();
        return subnets == null ? 0 : pool.getQuantityPerSubnet() * subnets.size();
    }

    static Map<String, Object> nodeConfigDetails(Map<String, Object> details, boolean create) {
        if (details == null) {
            return null;
        }
        Object size = details.get("size");
        if (size == null && create) {
            throw OciException.missingParameter("Missing required parameter: nodeConfigDetails.size");
        }
        if (size != null && (!(size instanceof Number number) || number.intValue() < 0
                || number.doubleValue() != number.intValue())) {
            throw OciException.invalidParameter("nodeConfigDetails.size must be a non-negative integer");
        }
        Object placements = details.get("placementConfigs");
        if (placements == null && create) {
            throw OciException.missingParameter("Missing required parameter: nodeConfigDetails.placementConfigs");
        }
        Map<String, Object> kept = only(details, NODE_CONFIG_DETAILS);
        if (placements != null) {
            if (!(placements instanceof List<?> list)) {
                throw OciException.invalidParameter("nodeConfigDetails.placementConfigs must be a list");
            }
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (Object placement : list) {
                normalized.add(placementConfig(placement));
            }
            kept.put("placementConfigs", normalized);
        }
        return kept;
    }

    private static Map<String, Object> placementConfig(Object placement) {
        if (!(placement instanceof Map<?, ?> map)) {
            throw OciException.invalidParameter("nodeConfigDetails.placementConfigs entries must be objects");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) map;
        for (String required : List.of("availabilityDomain", "subnetId")) {
            if (!(config.get(required) instanceof String value) || value.isBlank()) {
                throw OciException.missingParameter(
                        "Missing required parameter: nodeConfigDetails.placementConfigs." + required);
            }
        }
        if (config.get("faultDomains") instanceof List<?> faultDomains) {
            for (Object faultDomain : faultDomains) {
                if (!String.valueOf(faultDomain).matches("FAULT-DOMAIN-[1-3]")) {
                    throw OciException.invalidParameter("Invalid fault domain: " + faultDomain);
                }
            }
        }
        return only(config, PLACEMENT_CONFIG);
    }

    static Map<String, Object> shapeConfig(Map<String, Object> shapeConfig) {
        if (shapeConfig == null) {
            return null;
        }
        for (String key : SHAPE_CONFIG) {
            Object value = shapeConfig.get(key);
            if (value != null && (!(value instanceof Number number) || number.doubleValue() <= 0)) {
                throw OciException.invalidParameter("nodeShapeConfig." + key + " must be a positive number");
            }
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        for (String key : List.of("ocpus", "memoryInGBs")) {
            if (shapeConfig.get(key) instanceof Number number) {
                kept.put(key, number.floatValue());
            }
        }
        return kept;
    }

    static List<Map<String, Object>> initialNodeLabels(List<Map<String, Object>> labels) {
        if (labels == null) {
            return null;
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> label : labels) {
            if (label == null || !(label.get("key") instanceof String key) || key.isBlank()) {
                throw OciException.missingParameter("Missing required parameter: initialNodeLabels.key");
            }
            Map<String, Object> keyValue = new LinkedHashMap<>();
            keyValue.put("key", key);
            keyValue.put("value", label.get("value") != null ? String.valueOf(label.get("value")) : "");
            kept.add(keyValue);
        }
        return kept;
    }

    static Map<String, Object> cyclingDetails(Map<String, Object> details) {
        return details == null ? null : only(details, CYCLING_DETAILS);
    }

    static Map<String, Object> evictionSettings(Map<String, Object> settings) {
        return settings == null ? null : only(settings, EVICTION_SETTINGS);
    }

    /** The placements nodes rotate through: the placement configs, or one per legacy subnet. */
    static List<Placement> placements(StoredNodePool pool, Supplier<String> defaultAvailabilityDomain) {
        List<Placement> placements = new ArrayList<>();
        Map<String, Object> nodeConfig = pool.getNodeConfigDetails();
        if (nodeConfig != null && nodeConfig.get("placementConfigs") instanceof List<?> configs && !configs.isEmpty()) {
            for (Object config : configs) {
                Map<?, ?> placement = (Map<?, ?>) config;
                List<String> faultDomains = placement.get("faultDomains") instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList()
                        : List.of();
                placements.add(new Placement((String) placement.get("availabilityDomain"),
                        (String) placement.get("subnetId"), faultDomains));
            }
            return placements;
        }
        if (pool.getSubnetIds() != null) {
            String availabilityDomain = defaultAvailabilityDomain.get();
            for (String subnetId : pool.getSubnetIds()) {
                placements.add(new Placement(availabilityDomain, subnetId, List.of()));
            }
        }
        return placements;
    }

    /**
     * Brings {@code pool.nodes} to the requested count: new nodes rotate through the placements and
     * take the lowest free address and slot, and a smaller count removes the newest nodes first.
     *
     * @param usedHosts the host numbers (last octet) already taken in the cluster's node subnet
     * @return the nodes removed from the pool
     */
    static List<StoredNode> resize(StoredNodePool pool, String clusterId, String nodeSubnet, Set<Integer> usedHosts,
                                   Supplier<String> instanceOcid, Supplier<String> defaultAvailabilityDomain) {
        int target = requestedCount(pool);
        List<StoredNode> nodes = pool.getNodes();
        List<StoredNode> removed = new ArrayList<>();
        while (nodes.size() > target) {
            removed.add(nodes.removeLast());
        }
        List<Placement> placements = placements(pool, defaultAvailabilityDomain);
        if (placements.isEmpty()) {
            return removed;
        }
        while (nodes.size() < target) {
            Placement placement = placements.get(nodes.size() % placements.size());
            StoredNode node = new StoredNode();
            node.setId(instanceOcid.get());
            node.setNodePoolId(pool.getId());
            node.setAvailabilityDomain(placement.availabilityDomain());
            node.setSubnetId(placement.subnetId());
            node.setFaultDomain(faultDomain(nodes, placement));
            node.setName(nodeName(clusterId, pool.getId(), placement.subnetId(), freeSlot(nodes, placement.subnetId())));
            node.setKubernetesVersion(pool.getKubernetesVersion());
            node.setPrivateIp(nextAddress(nodeSubnet, usedHosts));
            node.setLifecycleState("CREATING");
            nodes.add(node);
        }
        return removed;
    }

    /**
     * {@code oke-c<part-of-cluster-OCID>-n<part-of-node-pool-OCID>-s<part-of-subnet-OCID>-<slot>},
     * Oracle's "Generated Worker Node Name Formats" for managed nodes.
     */
    static String nodeName(String clusterId, String nodePoolId, String subnetId, int slot) {
        return "oke-c" + namePart(clusterId) + "-n" + namePart(nodePoolId) + "-s" + namePart(subnetId) + "-" + slot;
    }

    private static String namePart(String ocid) {
        String unique = ocid == null ? "" : ocid.substring(ocid.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        if (unique.length() < NAME_PART_LENGTH) {
            return (unique + "a".repeat(NAME_PART_LENGTH)).substring(0, NAME_PART_LENGTH);
        }
        return unique.substring(unique.length() - NAME_PART_LENGTH);
    }

    private static int freeSlot(List<StoredNode> nodes, String subnetId) {
        Set<String> names = new HashSet<>();
        for (StoredNode node : nodes) {
            if (subnetId != null && subnetId.equals(node.getSubnetId())) {
                names.add(node.getName().substring(node.getName().lastIndexOf('-') + 1));
            }
        }
        int slot = 0;
        while (names.contains(String.valueOf(slot))) {
            slot++;
        }
        return slot;
    }

    /** The placement's fault domains in turn, else all three of its availability domain in turn. */
    private static String faultDomain(List<StoredNode> nodes, Placement placement) {
        long sameSpot = nodes.stream()
                .filter(node -> placement.subnetId().equals(node.getSubnetId())
                        && Objects.equals(placement.availabilityDomain(), node.getAvailabilityDomain()))
                .count();
        if (!placement.faultDomains().isEmpty()) {
            return placement.faultDomains().get((int) (sameSpot % placement.faultDomains().size()));
        }
        long inAvailabilityDomain = nodes.stream()
                .filter(node -> Objects.equals(placement.availabilityDomain(), node.getAvailabilityDomain()))
                .count();
        return "FAULT-DOMAIN-" + (inAvailabilityDomain % FAULT_DOMAINS_PER_AD + 1);
    }

    /** The lowest free node address of a {@code /24} node subnet, which it then marks used. */
    static String nextAddress(String nodeSubnet, Set<Integer> usedHosts) {
        String prefix = nodeSubnet.substring(0, nodeSubnet.lastIndexOf('.') + 1);
        for (int host = FIRST_NODE_HOST; host <= LAST_NODE_HOST; host++) {
            if (usedHosts.add(host)) {
                return prefix + host;
            }
        }
        throw OciException.limitExceeded("No free node address left in " + nodeSubnet);
    }

    static int hostOf(String address) {
        return Integer.parseInt(address.substring(address.lastIndexOf('.') + 1));
    }

    private static Map<String, Object> only(Map<String, Object> object, Set<String> keys) {
        Map<String, Object> kept = new LinkedHashMap<>();
        object.forEach((key, value) -> {
            if (keys.contains(key) && value != null) {
                kept.put(key, value);
            }
        });
        return kept;
    }
}
