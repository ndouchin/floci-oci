package io.floci.oci.services.oke;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.oci.services.oke.model.StoredOkeCluster;

import java.util.List;
import java.util.Optional;

/** What the load balancer controller reads from and writes to a cluster's Kubernetes API. */
interface OkeLoadBalancerKube {

    record Snapshot(List<JsonNode> services, List<JsonNode> nodes, List<JsonNode> endpointSlices) {
    }

    /** Every Service, Node and EndpointSlice; empty when the API cannot be read. */
    Optional<Snapshot> snapshot(StoredOkeCluster cluster);

    /** A JSON merge patch of the Service object. */
    boolean patchService(StoredOkeCluster cluster, String namespace, String name, String mergePatch);

    /** A JSON merge patch of the Service's {@code status} subresource. */
    boolean patchServiceStatus(StoredOkeCluster cluster, String namespace, String name, String mergePatch);

    /** A core/v1 Event about the Service, from {@code service-controller} as a cloud controller reports. */
    void event(StoredOkeCluster cluster, OkeLoadBalancerSpec service, String type, String reason, String message);
}
