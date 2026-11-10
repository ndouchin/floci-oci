package io.floci.oci.services.oke.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One node of a node pool: the oci-go-sdk {@code containerengine.Node} fields, plus where floci-oci
 * runs it. {@code containerName}, {@code volumeName} and {@code running} are internal and never part
 * of {@link #toWire()}.
 */
@RegisterForReflection
public class StoredNode {

    private String id;
    private String name;
    private String kubernetesVersion;
    private String availabilityDomain;
    private String subnetId;
    private String nodePoolId;
    private String faultDomain;
    private String privateIp;
    private String lifecycleState;
    private String lifecycleDetails;
    private String containerName;
    private String volumeName;
    private boolean running;

    /** The {@code Node} wire shape. {@code publicIp} is absent: the nodes sit on a private network. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", id);
        wire.put("name", name);
        wire.put("kubernetesVersion", kubernetesVersion);
        wire.put("availabilityDomain", availabilityDomain);
        wire.put("subnetId", subnetId);
        wire.put("nodePoolId", nodePoolId);
        wire.put("faultDomain", faultDomain);
        wire.put("privateIp", privateIp);
        wire.put("freeformTags", Map.of());
        wire.put("definedTags", Map.of());
        wire.put("systemTags", Map.of());
        wire.put("lifecycleState", lifecycleState);
        if (lifecycleDetails != null) {
            wire.put("lifecycleDetails", lifecycleDetails);
        }
        return wire;
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

    public String getKubernetesVersion() {
        return kubernetesVersion;
    }

    public void setKubernetesVersion(String kubernetesVersion) {
        this.kubernetesVersion = kubernetesVersion;
    }

    public String getAvailabilityDomain() {
        return availabilityDomain;
    }

    public void setAvailabilityDomain(String availabilityDomain) {
        this.availabilityDomain = availabilityDomain;
    }

    public String getSubnetId() {
        return subnetId;
    }

    public void setSubnetId(String subnetId) {
        this.subnetId = subnetId;
    }

    public String getNodePoolId() {
        return nodePoolId;
    }

    public void setNodePoolId(String nodePoolId) {
        this.nodePoolId = nodePoolId;
    }

    public String getFaultDomain() {
        return faultDomain;
    }

    public void setFaultDomain(String faultDomain) {
        this.faultDomain = faultDomain;
    }

    /** The node's address on its cluster's Docker network, which is also its Kubernetes node name. */
    public String getPrivateIp() {
        return privateIp;
    }

    public void setPrivateIp(String privateIp) {
        this.privateIp = privateIp;
    }

    public String getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(String lifecycleState) {
        this.lifecycleState = lifecycleState;
    }

    public String getLifecycleDetails() {
        return lifecycleDetails;
    }

    public void setLifecycleDetails(String lifecycleDetails) {
        this.lifecycleDetails = lifecycleDetails;
    }

    /** The k3s agent container backing the node; null when the node has none (mock mode, node cap). */
    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    /** The named volume holding the agent's k3s state and the node's local volumes. */
    public String getVolumeName() {
        return volumeName;
    }

    public void setVolumeName(String volumeName) {
        this.volumeName = volumeName;
    }

    /** Whether floci-oci runs a k3s agent for this node, or left it out because of the node cap. */
    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }
}
