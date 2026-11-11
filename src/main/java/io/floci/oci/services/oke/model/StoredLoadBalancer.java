package io.floci.oci.services.oke.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The load balancer floci-oci runs for one Service of type LoadBalancer of a real-mode cluster,
 * stored under {@code <clusterId>/<serviceUid>} in the cluster's partition, so its addresses survive
 * a restart. Never on the OCI wire: floci-oci emulates no Load Balancer service.
 */
@RegisterForReflection
public class StoredLoadBalancer {

    private String clusterId;
    private String serviceUid;
    private String namespace;
    private String name;
    /** {@code lb} or {@code nlb}, from {@code oci.oraclecloud.com/load-balancer-type}. */
    private String kind;
    private boolean internal;
    private String privateIp;
    private String publicIp;
    private String requestedPublicIp;
    private String requestedPrivateIp;
    private String applianceName;
    /** {@code <PROTOCOL>/<port>} to the port published on the Docker host's {@code 127.0.0.1}. */
    private Map<String, Integer> hostPorts = new LinkedHashMap<>();
    /** The node addresses the appliance forwards to, as of the last pass. */
    private List<String> backends = new ArrayList<>();
    private String firstSeen;
    private boolean ensured;

    public StoredLoadBalancer() {
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public String getServiceUid() {
        return serviceUid;
    }

    public void setServiceUid(String serviceUid) {
        this.serviceUid = serviceUid;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public boolean isInternal() {
        return internal;
    }

    public void setInternal(boolean internal) {
        this.internal = internal;
    }

    public String getPrivateIp() {
        return privateIp;
    }

    public void setPrivateIp(String privateIp) {
        this.privateIp = privateIp;
    }

    public String getPublicIp() {
        return publicIp;
    }

    public void setPublicIp(String publicIp) {
        this.publicIp = publicIp;
    }

    public String getRequestedPublicIp() {
        return requestedPublicIp;
    }

    public void setRequestedPublicIp(String requestedPublicIp) {
        this.requestedPublicIp = requestedPublicIp;
    }

    public String getRequestedPrivateIp() {
        return requestedPrivateIp;
    }

    public void setRequestedPrivateIp(String requestedPrivateIp) {
        this.requestedPrivateIp = requestedPrivateIp;
    }

    public String getApplianceName() {
        return applianceName;
    }

    public void setApplianceName(String applianceName) {
        this.applianceName = applianceName;
    }

    public Map<String, Integer> getHostPorts() {
        return hostPorts;
    }

    public void setHostPorts(Map<String, Integer> hostPorts) {
        this.hostPorts = hostPorts != null ? new LinkedHashMap<>(hostPorts) : new LinkedHashMap<>();
    }

    public List<String> getBackends() {
        return backends;
    }

    public void setBackends(List<String> backends) {
        this.backends = backends != null ? new ArrayList<>(backends) : new ArrayList<>();
    }

    public String getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(String firstSeen) {
        this.firstSeen = firstSeen;
    }

    public boolean isEnsured() {
        return ensured;
    }

    public void setEnsured(boolean ensured) {
        this.ensured = ensured;
    }
}
