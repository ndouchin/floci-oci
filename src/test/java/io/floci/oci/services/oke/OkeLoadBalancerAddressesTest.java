package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;
import io.floci.oci.services.oke.model.StoredLoadBalancer;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkeLoadBalancerAddressesTest {

    @Test
    void publicAddressesComeFromTheTestNetRangeSkippingTheOnesInUse() {
        assertEquals("192.0.2.10", OkeLoadBalancerAddresses.nextPublic("192.0.2.0/24", Set.of()));
        assertEquals("192.0.2.12", OkeLoadBalancerAddresses.nextPublic("192.0.2.0/24",
                Set.of("192.0.2.10", "192.0.2.11")));
    }

    @Test
    void thePublicRangeLeavesItsLowAddressesToTheGatewayAndItsTopToDocker() {
        Set<String> used = new HashSet<>();
        for (int host = 10; host <= 239; host++) {
            used.add("192.0.2." + host);
        }

        assertThrows(IllegalStateException.class, () -> OkeLoadBalancerAddresses.nextPublic("192.0.2.0/24", used));
        assertEquals("192.0.2.240/28", OkeLoadBalancerAddresses.dockerRange("192.0.2.0/24"));
    }

    @Test
    void internalAddressesComeFromTheTopOfTheNodeSubnetDownToTheFirstNodeAddress() {
        assertEquals("10.0.10.254", OkeLoadBalancerAddresses.nextInternal("10.0.10.0/24", Set.of()));
        assertEquals("10.0.10.252", OkeLoadBalancerAddresses.nextInternal("10.0.10.0/24",
                Set.of("10.0.10.254", "10.0.10.253")));
        Set<String> full = new HashSet<>();
        for (int host = OkeNodePools.FIRST_NODE_HOST; host <= 254; host++) {
            full.add("10.0.10." + host);
        }
        assertThrows(OciException.class, () -> OkeLoadBalancerAddresses.nextInternal("10.0.10.0/24", full));
    }

    @Test
    void containmentInARange() {
        assertTrue(OkeLoadBalancerAddresses.inCidr("192.0.2.50", "192.0.2.0/24"));
        assertFalse(OkeLoadBalancerAddresses.inCidr("192.0.3.50", "192.0.2.0/24"));
        assertTrue(OkeLoadBalancerAddresses.inCidr("10.0.10.200", "10.0.10.0/24"));
        assertFalse(OkeLoadBalancerAddresses.inCidr("not-an-ip", "10.0.10.0/24"));
    }

    @Test
    void statusListsWhatEachKindOfLoadBalancerReports() {
        // loadBalancerToStatus, load_balancer.go 2028-2055: one entry per address, private ones
        // skipped for a public NLB with external-ip-only.
        assertEquals(List.of(Map.of("ip", "192.0.2.10")),
                OkeLoadBalancerAddresses.ingress(record("lb", false, "10.0.10.254", "192.0.2.10"), false, null));
        assertEquals(List.of(Map.of("ip", "10.0.10.254")),
                OkeLoadBalancerAddresses.ingress(record("lb", true, "10.0.10.254", null), false, null));
        assertEquals(List.of(Map.of("ip", "10.0.10.254"), Map.of("ip", "192.0.2.10")),
                OkeLoadBalancerAddresses.ingress(record("nlb", false, "10.0.10.254", "192.0.2.10"), false, null));
        assertEquals(List.of(Map.of("ip", "192.0.2.10")),
                OkeLoadBalancerAddresses.ingress(record("nlb", false, "10.0.10.254", "192.0.2.10"), true, null));
        assertEquals(List.of(Map.of("ip", "10.0.10.254", "ipMode", "Proxy")),
                OkeLoadBalancerAddresses.ingress(record("nlb", true, "10.0.10.254", null), false, "Proxy"));
    }

    @Test
    void theHostPortsAnnotationListsEachPortAndItsPublishedAddress() {
        Map<String, Integer> hostPorts = new LinkedHashMap<>();
        hostPorts.put("TCP/80", 41234);
        hostPorts.put("UDP/53", 41235);

        assertEquals("TCP/80=127.0.0.1:41234,UDP/53=127.0.0.1:41235",
                OkeLoadBalancerAddresses.hostPortsAnnotation(hostPorts));
    }

    private static StoredLoadBalancer record(String kind, boolean internal, String privateIp, String publicIp) {
        StoredLoadBalancer record = new StoredLoadBalancer();
        record.setKind(kind);
        record.setInternal(internal);
        record.setPrivateIp(privateIp);
        record.setPublicIp(publicIp);
        return record;
    }
}
