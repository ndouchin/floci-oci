package io.floci.oci.services.oke.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage entity representing an OCI Container Engine for Kubernetes (OKE) Node Pool. The optional
 * settings of CreateNodePoolDetails are kept as the client sent them, reduced to their SDK fields.
 */
@RegisterForReflection
public class StoredNodePool {

    private String id;
    private String name;
    private String compartmentId;
    private String clusterId;
    private String kubernetesVersion;
    private String nodeShape;
    private int quantityPerSubnet;
    private String lifecycleState;
    @JsonIgnore
    private Instant timeCreated;
    private Map<String, String> freeformTags;
    private Map<String, Map<String, Object>> definedTags;
    private String lifecycleDetails;
    private List<String> subnetIds;
    private Map<String, Object> nodeConfigDetails;
    private Map<String, Object> nodeShapeConfig;
    private List<Map<String, Object>> initialNodeLabels;
    private Map<String, Object> nodeSourceDetails;
    private String sshPublicKey;
    private Map<String, String> nodeMetadata;
    private Map<String, Object> nodePoolCyclingDetails;
    private Map<String, Object> nodeEvictionNodePoolSettings;
    private List<StoredNode> nodes = new ArrayList<>();
    private String tenancyId;
    private String region;
    private String pendingWorkRequestId;

    /**
     * The {@code NodePool} wire shape. {@code systemTags} is always present, as on the cluster: the
     * oracle/oci provider (9.12+) declares {@code system_tags} computed and reads it back only when
     * the response carries it, so an absent field plans an in-place update after every apply.
     */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("lifecycleState", lifecycleState);
        if (lifecycleDetails != null) {
            wire.put("lifecycleDetails", lifecycleDetails);
        }
        wire.put("compartmentId", compartmentId);
        wire.put("clusterId", clusterId);
        wire.put("name", name);
        wire.put("kubernetesVersion", kubernetesVersion);
        putIfPresent(wire, "nodeMetadata", nodeMetadata);
        wire.put("nodeShape", nodeShape);
        putIfPresent(wire, "nodeShapeConfig", nodeShapeConfig);
        putIfPresent(wire, "nodeSourceDetails", nodeSourceDetails);
        putIfPresent(wire, "initialNodeLabels", initialNodeLabels);
        putIfPresent(wire, "sshPublicKey", sshPublicKey);
        wire.put("quantityPerSubnet", quantityPerSubnet);
        putIfPresent(wire, "subnetIds", subnetIds);
        wire.put("nodes", nodes.stream().map(StoredNode::toWire).toList());
        putIfPresent(wire, "nodeConfigDetails", nodeConfigDetails);
        putIfPresent(wire, "freeformTags", freeformTags);
        putIfPresent(wire, "definedTags", definedTags);
        wire.put("systemTags", Map.of());
        putIfPresent(wire, "nodeEvictionNodePoolSettings", nodeEvictionNodePoolSettings);
        putIfPresent(wire, "nodePoolCyclingDetails", nodePoolCyclingDetails);
        return wire;
    }

    private static void putIfPresent(Map<String, Object> wire, String key, Object value) {
        if (value != null) {
            wire.put(key, value);
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    public void setCompartmentId(String compartmentId) {
        this.compartmentId = compartmentId;
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public String getKubernetesVersion() {
        return kubernetesVersion;
    }

    public void setKubernetesVersion(String kubernetesVersion) {
        this.kubernetesVersion = kubernetesVersion;
    }

    public String getNodeShape() {
        return nodeShape;
    }

    public void setNodeShape(String nodeShape) {
        this.nodeShape = nodeShape;
    }

    public int getQuantityPerSubnet() {
        return quantityPerSubnet;
    }

    public void setQuantityPerSubnet(int quantityPerSubnet) {
        this.quantityPerSubnet = quantityPerSubnet;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public Instant getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(Instant timeCreated) {
        this.timeCreated = timeCreated;
    }

    public Map<String, String> getFreeformTags() {
        return freeformTags;
    }

    public void setFreeformTags(Map<String, String> freeformTags) {
        this.freeformTags = freeformTags;
    }

    public Map<String, Map<String, Object>> getDefinedTags() {
        return definedTags;
    }

    public void setDefinedTags(Map<String, Map<String, Object>> definedTags) {
        this.definedTags = definedTags;
    }

    public String getLifecycleDetails() {
        return lifecycleDetails;
    }

    public void setLifecycleDetails(String lifecycleDetails) {
        this.lifecycleDetails = lifecycleDetails;
    }

    public List<String> getSubnetIds() {
        return subnetIds;
    }

    public void setSubnetIds(List<String> subnetIds) {
        this.subnetIds = subnetIds;
    }

    public Map<String, Object> getNodeConfigDetails() {
        return nodeConfigDetails;
    }

    public void setNodeConfigDetails(Map<String, Object> nodeConfigDetails) {
        this.nodeConfigDetails = nodeConfigDetails;
    }

    public Map<String, Object> getNodeShapeConfig() {
        return nodeShapeConfig;
    }

    public void setNodeShapeConfig(Map<String, Object> nodeShapeConfig) {
        this.nodeShapeConfig = nodeShapeConfig;
    }

    public List<Map<String, Object>> getInitialNodeLabels() {
        return initialNodeLabels;
    }

    public void setInitialNodeLabels(List<Map<String, Object>> initialNodeLabels) {
        this.initialNodeLabels = initialNodeLabels;
    }

    public Map<String, Object> getNodeSourceDetails() {
        return nodeSourceDetails;
    }

    public void setNodeSourceDetails(Map<String, Object> nodeSourceDetails) {
        this.nodeSourceDetails = nodeSourceDetails;
    }

    public String getSshPublicKey() {
        return sshPublicKey;
    }

    public void setSshPublicKey(String sshPublicKey) {
        this.sshPublicKey = sshPublicKey;
    }

    public Map<String, String> getNodeMetadata() {
        return nodeMetadata;
    }

    public void setNodeMetadata(Map<String, String> nodeMetadata) {
        this.nodeMetadata = nodeMetadata;
    }

    public Map<String, Object> getNodePoolCyclingDetails() {
        return nodePoolCyclingDetails;
    }

    public void setNodePoolCyclingDetails(Map<String, Object> nodePoolCyclingDetails) {
        this.nodePoolCyclingDetails = nodePoolCyclingDetails;
    }

    public Map<String, Object> getNodeEvictionNodePoolSettings() {
        return nodeEvictionNodePoolSettings;
    }

    public void setNodeEvictionNodePoolSettings(Map<String, Object> nodeEvictionNodePoolSettings) {
        this.nodeEvictionNodePoolSettings = nodeEvictionNodePoolSettings;
    }

    public List<StoredNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<StoredNode> nodes) {
        this.nodes = nodes != null ? nodes : new ArrayList<>();
    }

    /** Owning tenancy, so the readiness poller can address the pool's partition. Internal. */
    public String getTenancyId() {
        return tenancyId;
    }

    public void setTenancyId(String tenancyId) {
        this.tenancyId = tenancyId;
    }

    /** Region the pool was created in. Internal. */
    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    /** The NODEPOOL_CREATE or NODEPOOL_UPDATE work request finished once the pool's nodes are Ready. */
    public String getPendingWorkRequestId() {
        return pendingWorkRequestId;
    }

    public void setPendingWorkRequestId(String pendingWorkRequestId) {
        this.pendingWorkRequestId = pendingWorkRequestId;
    }
}
