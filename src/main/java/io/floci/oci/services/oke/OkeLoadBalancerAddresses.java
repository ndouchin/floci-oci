package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;
import io.floci.oci.services.oke.model.StoredLoadBalancer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The addresses floci-oci gives load balancers, and the status that reports them.
 *
 * <p>Public addresses come from one range shared by every cluster ({@code load-balancer.public-cidr},
 * RFC 5737's {@code 192.0.2.0/24} by default, the range of Oracle's own example, "EXTERNAL-IP
 * 192.0.2.22"). They start at the tenth address, leaving the low ones to the Docker network's gateway,
 * and stop below the top {@code /28}, which that network leaves to Docker. Private addresses come
 * from the cluster's node subnet, from {@code .254} down to the first node address, so they never meet
 * the node addresses taken from {@code .16} up.
 */
final class OkeLoadBalancerAddresses {

    private static final int FIRST_PUBLIC_OFFSET = 10;
    private static final int DOCKER_RANGE_PREFIX = 28;
    private static final int LAST_INTERNAL_HOST = 254;
    static final String HOST_PORT_ADDRESS = "127.0.0.1";

    private OkeLoadBalancerAddresses() {
    }

    /** The lowest free public address of {@code cidr}, which {@code used} holds none of. */
    static String nextPublic(String cidr, Set<String> used) {
        long network = network(cidr);
        long last = dockerRangeStart(cidr) - 1;
        for (long address = network + FIRST_PUBLIC_OFFSET; address <= last; address++) {
            String candidate = format(address);
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No free public load balancer address left in " + cidr);
    }

    /** Whether {@code address} is one {@link #nextPublic} could hand out. */
    static boolean isAllocatablePublic(String address, String cidr) {
        if (!inCidr(address, cidr)) {
            return false;
        }
        long value = parse(address);
        return value >= network(cidr) + FIRST_PUBLIC_OFFSET && value < dockerRangeStart(cidr);
    }

    /** The top {@code /28} of {@code cidr}, which the public Docker network hands out on its own. */
    static String dockerRange(String cidr) {
        return format(dockerRangeStart(cidr)) + "/" + DOCKER_RANGE_PREFIX;
    }

    /** The highest free private address of a cluster's {@code /24} node subnet, above the node addresses. */
    static String nextInternal(String nodeSubnet, Set<String> used) {
        String prefix = nodeSubnet.substring(0, nodeSubnet.lastIndexOf('.') + 1);
        for (int host = LAST_INTERNAL_HOST; host >= OkeNodePools.FIRST_NODE_HOST; host--) {
            String candidate = prefix + host;
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        throw OciException.limitExceeded("No free private load balancer address left in " + nodeSubnet);
    }

    /** Whether {@code address} is a private address a load balancer of a {@code /24} node subnet may take. */
    static boolean isAllocatableInternal(String address, String nodeSubnet) {
        if (!inCidr(address, nodeSubnet)) {
            return false;
        }
        int host = (int) (parse(address) & 0xFF);
        return host >= OkeNodePools.FIRST_NODE_HOST && host <= LAST_INTERNAL_HOST;
    }

    static boolean inCidr(String address, String cidr) {
        try {
            int prefix = Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
            long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
            return (parse(address) & mask) == (parse(cidr.substring(0, cidr.indexOf('/'))) & mask);
        } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
            return false;
        }
    }

    /**
     * {@code status.loadBalancer.ingress}: one entry per address the load balancer has, as OKE's
     * {@code loadBalancerToStatus} reports them, the private one skipped for a public NLB with
     * {@code external-ip-only}. A public OCI Load Balancer has only its public address; a public NLB
     * has a private one too (inferred from the CCM code, not stated by Oracle), listed first.
     */
    static List<Map<String, String>> ingress(StoredLoadBalancer record, boolean externalIpOnly, String ipMode) {
        List<String> addresses = new ArrayList<>();
        boolean nlb = OkeLoadBalancerSpec.Kind.NLB.value().equals(record.getKind());
        if (record.isInternal()) {
            addresses.add(record.getPrivateIp());
        } else {
            if (nlb && !externalIpOnly && record.getPrivateIp() != null) {
                addresses.add(record.getPrivateIp());
            }
            addresses.add(record.getPublicIp());
        }
        List<Map<String, String>> ingress = new ArrayList<>();
        for (String address : addresses) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("ip", address);
            if (ipMode != null) {
                entry.put("ipMode", ipMode);
            }
            ingress.add(Map.copyOf(entry));
        }
        return List.copyOf(ingress);
    }

    /** {@code TCP/80=127.0.0.1:41234,UDP/53=127.0.0.1:41235}, the value of {@code floci.io/load-balancer-host-ports}. */
    static String hostPortsAnnotation(Map<String, Integer> hostPorts) {
        StringJoiner joined = new StringJoiner(",");
        hostPorts.forEach((port, hostPort) -> joined.add(port + "=" + HOST_PORT_ADDRESS + ":" + hostPort));
        return joined.toString();
    }

    private static long network(String cidr) {
        int prefix = Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return parse(cidr.substring(0, cidr.indexOf('/'))) & mask;
    }

    private static long dockerRangeStart(String cidr) {
        int prefix = Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
        long broadcast = network(cidr) + (1L << (32 - prefix)) - 1;
        return broadcast - (1L << (32 - DOCKER_RANGE_PREFIX)) + 1;
    }

    private static long parse(String address) {
        String[] octets = address.split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("Not an IPv4 address: " + address);
        }
        long value = 0;
        for (String octet : octets) {
            int part = Integer.parseInt(octet);
            if (part < 0 || part > 255) {
                throw new IllegalArgumentException("Not an IPv4 address: " + address);
            }
            value = (value << 8) | part;
        }
        return value;
    }

    private static String format(long address) {
        return ((address >> 24) & 0xFF) + "." + ((address >> 16) & 0xFF) + "." + ((address >> 8) & 0xFF) + "."
                + (address & 0xFF);
    }
}
