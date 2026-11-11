package io.floci.oci.services.oke;

import io.floci.oci.services.oke.model.StoredLoadBalancer;
import io.floci.oci.services.oke.model.StoredOkeCluster;

import java.time.Instant;
import java.util.List;

/** The stored load balancers of a cluster, and the addresses they hold. */
interface OkeLoadBalancerRecords {

    /** @param changed whether the record is new or its addresses changed */
    record Reservation(StoredLoadBalancer record, boolean changed) {
    }

    List<StoredLoadBalancer> list(StoredOkeCluster cluster);

    /**
     * Gives the Service's load balancer its addresses, keeping those it already has while they still
     * fit the Service, and stores the record.
     *
     * @throws RuntimeException with the reason when an address the Service asks for cannot be had
     */
    Reservation reserve(StoredOkeCluster cluster, OkeLoadBalancerSpec spec, Instant now);

    void save(StoredOkeCluster cluster, StoredLoadBalancer record);

    void release(StoredOkeCluster cluster, String serviceUid);
}
