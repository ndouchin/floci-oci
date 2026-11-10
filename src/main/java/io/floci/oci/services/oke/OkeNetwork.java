package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;

import java.util.Map;

/**
 * The cluster's pod and service networks, from {@code options.kubernetesNetworkConfig}. The defaults
 * are the ones oci-go-sdk {@code KubernetesNetworkConfig} documents: "The CIDR block for Kubernetes
 * pods. Optional, defaults to 10.244.0.0/16." and services "defaults to 10.96.0.0/16". OKE's
 * cluster DNS service sits at {@code 10.96.5.5} in the default service network (seen in Oracle's
 * Cilium on OKE tutorial, not documented); floci-oci takes the same offset in any service network
 * large enough, else the tenth address, k3s's default. "Kubernetes Engine allocates a /25 CIDR block
 * of network addresses for each worker node" (IPv4 CIDR Blocks and Kubernetes Engine), so the pods
 * network must hold at least one {@code /25}.
 */
final class OkeNetwork {

    static final String DEFAULT_PODS_CIDR = "10.244.0.0/16";
    static final String DEFAULT_SERVICES_CIDR = "10.96.0.0/16";
    private static final int DNS_OFFSET = 5 * 256 + 5;
    private static final int SMALL_NETWORK_DNS_OFFSET = 10;
    static final int NODE_PODS_PREFIX = 25;

    record Cidrs(String pods, String services, String dns) {}

    private OkeNetwork() {
    }

    static Cidrs of(Map<String, Object> options) {
        String pods = DEFAULT_PODS_CIDR;
        String services = DEFAULT_SERVICES_CIDR;
        if (options != null && options.get("kubernetesNetworkConfig") instanceof Map<?, ?> config) {
            if (config.get("podsCidr") instanceof String value && !value.isBlank()) {
                pods = value;
            }
            if (config.get("servicesCidr") instanceof String value && !value.isBlank()) {
                services = value;
            }
        }
        return new Cidrs(pods, services, dnsAddress(services));
    }

    /**
     * Both networks must be IPv4 CIDR blocks that do not overlap, and the pods network must hold a
     * node's {@code /25}; 400 InvalidParameter otherwise.
     */
    static void validate(Map<String, Object> options) {
        Cidrs cidrs = of(options);
        long[] pods = parse(cidrs.pods(), "podsCidr");
        long[] services = parse(cidrs.services(), "servicesCidr");
        if (pods[1] - pods[0] + 1 < 1L << (32 - NODE_PODS_PREFIX)) {
            throw OciException.invalidParameter("options.kubernetesNetworkConfig.podsCidr " + cidrs.pods()
                    + " is smaller than the /" + NODE_PODS_PREFIX + " each node takes");
        }
        if (overlaps(pods, services)) {
            throw OciException.invalidParameter("options.kubernetesNetworkConfig.podsCidr " + cidrs.pods()
                    + " overlaps servicesCidr " + cidrs.services());
        }
    }

    static boolean overlaps(String a, String b) {
        return overlaps(parse(a, "cidr"), parse(b, "cidr"));
    }

    private static boolean overlaps(long[] a, long[] b) {
        return a[0] <= b[1] && b[0] <= a[1];
    }

    private static String dnsAddress(String services) {
        long[] range = parse(services, "servicesCidr");
        long size = range[1] - range[0] + 1;
        long offset = size > DNS_OFFSET + 1 ? DNS_OFFSET : SMALL_NETWORK_DNS_OFFSET;
        return format(range[0] + offset);
    }

    /** The first and last address of an IPv4 CIDR block. */
    private static long[] parse(String cidr, String field) {
        String[] parts = cidr.split("/");
        String[] octets = parts[0].split("\\.");
        if (parts.length != 2 || octets.length != 4) {
            throw invalid(cidr, field);
        }
        try {
            long address = 0;
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    throw invalid(cidr, field);
                }
                address = (address << 8) | value;
            }
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 8 || prefix > 28) {
                throw invalid(cidr, field);
            }
            long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            long first = address & mask;
            return new long[] {first, first | (~mask & 0xFFFFFFFFL)};
        } catch (NumberFormatException e) {
            throw invalid(cidr, field);
        }
    }

    private static OciException invalid(String cidr, String field) {
        return OciException.invalidParameter("Invalid options.kubernetesNetworkConfig." + field + ": " + cidr);
    }

    private static String format(long address) {
        return ((address >> 24) & 0xFF) + "." + ((address >> 16) & 0xFF) + "." + ((address >> 8) & 0xFF) + "."
                + (address & 0xFF);
    }
}
