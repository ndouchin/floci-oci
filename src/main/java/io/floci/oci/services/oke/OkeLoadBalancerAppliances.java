package io.floci.oci.services.oke;

import io.floci.oci.services.oke.model.StoredOkeCluster;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The containers that hold load balancer addresses and forward their ports to the nodes. */
interface OkeLoadBalancerAppliances {

    /**
     * One appliance: the addresses it holds, the ports it forwards to each backend node's NodePort,
     * and the host ports it would like to keep from an earlier container.
     */
    record Spec(String name, String serviceUid, boolean internal, String privateIp, String publicIp,
                List<OkeLoadBalancerSpec.Port> ports, List<String> backends, List<String> sourceRanges,
                Map<String, Integer> preferredHostPorts) {

        /** Whether a running container made from {@code other} serves this spec; the host ports are only a wish. */
        boolean sameAppliance(Spec other) {
            return other != null && name.equals(other.name) && serviceUid.equals(other.serviceUid)
                    && internal == other.internal && Objects.equals(privateIp, other.privateIp)
                    && Objects.equals(publicIp, other.publicIp) && ports.equals(other.ports)
                    && backends.equals(other.backends) && sourceRanges.equals(other.sourceRanges);
        }
    }

    String nameFor(String serviceUid);

    /**
     * Starts the appliance unless one serving {@code spec} already runs, replacing one that does not.
     *
     * @return the host port each {@code PROTOCOL/port} is published on, empty when none are
     */
    Map<String, Integer> ensure(StoredOkeCluster cluster, Spec spec);

    void remove(StoredOkeCluster cluster, String name);

    /** The names of the cluster's appliance containers, running or not. */
    Set<String> list(StoredOkeCluster cluster);
}
