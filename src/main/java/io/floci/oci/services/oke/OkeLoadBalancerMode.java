package io.floci.oci.services.oke;

import io.floci.oci.services.oke.model.StoredOkeCluster;

import java.util.Locale;

/** {@code floci-oci.services.oke.load-balancer.mode}: who answers Services of type LoadBalancer. */
public enum OkeLoadBalancerMode {

    /**
     * k3s's servicelb is off and floci-oci acts as OKE's cloud controller: it writes an OCI-shaped
     * address into the Service's status and runs one appliance container per Service holding it.
     */
    EMULATED("emulated"),

    /** k3s's bundled servicelb, which publishes the node addresses, as clusters created before had. */
    SERVICELB("servicelb"),

    /** servicelb is off and nothing answers: Services stay {@code <pending>}. */
    OFF("off");

    private final String value;

    OkeLoadBalancerMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /**
     * The mode the cluster was created with. A cluster stored without one was created before
     * floci-oci emulated load balancers, so its server keeps servicelb and so its Services' addresses.
     */
    static OkeLoadBalancerMode of(StoredOkeCluster cluster) {
        return cluster.getLoadBalancerMode() == null ? SERVICELB : parse(cluster.getLoadBalancerMode());
    }

    static OkeLoadBalancerMode parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (OkeLoadBalancerMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalStateException("Invalid floci-oci.services.oke.load-balancer.mode value '" + value
                + "': allowed values are emulated, servicelb and off");
    }
}
