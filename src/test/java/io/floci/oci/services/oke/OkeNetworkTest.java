package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkeNetworkTest {

    @Test
    void withoutANetworkConfigTheClusterGetsOkesDefaults() {
        assertEquals(new OkeNetwork.Cidrs("10.244.0.0/16", "10.96.0.0/16", "10.96.5.5"), OkeNetwork.of(null));
        assertEquals(new OkeNetwork.Cidrs("10.244.0.0/16", "10.96.0.0/16", "10.96.5.5"),
                OkeNetwork.of(Map.of("serviceLbSubnetIds", java.util.List.of())));
    }

    @Test
    void theNetworkConfigSetsThePodAndServiceNetworks() {
        OkeNetwork.Cidrs cidrs = OkeNetwork.of(Map.of("kubernetesNetworkConfig",
                Map.of("podsCidr", "172.20.0.0/16", "servicesCidr", "172.21.0.0/20")));
        assertEquals(new OkeNetwork.Cidrs("172.20.0.0/16", "172.21.0.0/20", "172.21.5.5"), cidrs);
        assertEquals("192.168.0.10", OkeNetwork.of(Map.of("kubernetesNetworkConfig",
                Map.of("servicesCidr", "192.168.0.0/24"))).dns());
    }

    @Test
    void invalidOrOverlappingNetworksAreRejected() {
        assertThrows(OciException.class, () -> OkeNetwork.validate(Map.of("kubernetesNetworkConfig",
                Map.of("podsCidr", "10.244.0.0"))));
        assertThrows(OciException.class, () -> OkeNetwork.validate(Map.of("kubernetesNetworkConfig",
                Map.of("podsCidr", "10.96.0.0/12"))));
        assertThrows(OciException.class, () -> OkeNetwork.validate(Map.of("kubernetesNetworkConfig",
                Map.of("podsCidr", "172.20.0.0/26"))), "each node takes a /25 of the pods network");
        OkeNetwork.validate(Map.of("kubernetesNetworkConfig", Map.of("podsCidr", "172.20.0.0/25")));
        assertTrue(OkeNetwork.overlaps("10.0.10.0/24", "10.0.0.0/16"));
        assertFalse(OkeNetwork.overlaps("10.0.10.0/24", "10.244.0.0/16"));
    }
}
