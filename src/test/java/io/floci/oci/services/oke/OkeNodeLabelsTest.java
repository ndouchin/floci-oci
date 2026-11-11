package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;
import io.floci.oci.services.oke.model.StoredNode;
import io.floci.oci.services.oke.model.StoredNodePool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkeNodeLabelsTest {

    @Test
    void registrationLabelsFollowWhatOkeAndItsCloudControllerSet() {
        Map<String, String> labels = OkeNodeLabels.registrationLabels(pool(), node(), "us-ashburn-1");

        assertEquals("VM.Standard.E4.Flex", labels.get("node.kubernetes.io/instance-type"));
        assertEquals("VM.Standard.E4.Flex", labels.get("beta.kubernetes.io/instance-type"));
        assertEquals("iad", labels.get("topology.kubernetes.io/region"));
        assertEquals("US-ASHBURN-AD-2", labels.get("topology.kubernetes.io/zone"));
        assertEquals("US-ASHBURN-AD-2", labels.get("failure-domain.beta.kubernetes.io/zone"));
        assertEquals("FAULT-DOMAIN-3", labels.get("oci.oraclecloud.com/fault-domain"));
        assertEquals("true", labels.get("oci.oraclecloud.com/node.info.managed"));
        assertEquals("oke-cabc-nabc-sabc-0", labels.get("displayName"));
        assertEquals("10.0.10.16", labels.get("internal_addr"));
        assertEquals("workers", labels.get("name"));
        assertEquals("v1.35", labels.get("node.info/kubeletVersion"));
        assertEquals("ocid1.compartment.oc1", labels.get("node.info/compartment.id_prefix"));
        assertEquals("aaaaaaaatestcompartment", labels.get("node.info/compartment.id_suffix"));
        assertEquals("payments", labels.get("team"));
        assertFalse(labels.containsKey("node-role.kubernetes.io/node"), "a kubelet may not set its own role");
        assertFalse(labels.containsKey("tier.kubernetes.io/class"));
    }

    @Test
    void labelsAKubeletMayNotSetComeThroughTheApi() {
        Map<String, String> labels = OkeNodeLabels.postRegistrationLabels(pool());

        assertEquals(Map.of("node-role.kubernetes.io/node", "", "tier.kubernetes.io/class", "gold"), labels);
        assertEquals("oci://ocid1.instance.oc1.iad.node1", OkeNodeLabels.providerId(node()));
        assertEquals("eu-frankfurt-1", OkeNodeLabels.regionLabel("eu-frankfurt-1"));
        assertEquals("phx", OkeNodeLabels.regionLabel("us-phoenix-1"));
    }

    @Test
    void kubeletSelfLabellingFollowsNodeRestriction() {
        assertTrue(OkeNodeLabels.kubeletMaySet("team"));
        assertTrue(OkeNodeLabels.kubeletMaySet("example.com/role"));
        assertTrue(OkeNodeLabels.kubeletMaySet("node.kubernetes.io/exclude-from-external-load-balancers"));
        assertTrue(OkeNodeLabels.kubeletMaySet("topology.kubernetes.io/zone"));
        assertFalse(OkeNodeLabels.kubeletMaySet("node-role.kubernetes.io/worker"));
        assertFalse(OkeNodeLabels.kubeletMaySet("kubernetes.io/role"));
    }

    @Test
    void initialNodeLabelsMustBeValidKubernetesLabels() {
        OkeNodeLabels.validate(List.of(Map.of("key", "example.com/team", "value", "a_b-c.d")));
        assertThrows(OciException.class, () -> OkeNodeLabels.validate(List.of(Map.of("key", "bad key", "value", "x"))));
        assertThrows(OciException.class, () -> OkeNodeLabels.validate(List.of(Map.of("key", "team", "value", "-x"))));
    }

    private static StoredNodePool pool() {
        StoredNodePool pool = new StoredNodePool();
        pool.setId("ocid1.nodepool.oc1.iad.pool");
        pool.setName("workers");
        pool.setCompartmentId("ocid1.compartment.oc1..aaaaaaaatestcompartment");
        pool.setNodeShape("VM.Standard.E4.Flex");
        pool.setInitialNodeLabels(List.of(Map.of("key", "team", "value", "payments"),
                Map.of("key", "tier.kubernetes.io/class", "value", "gold")));
        return pool;
    }

    private static StoredNode node() {
        StoredNode node = new StoredNode();
        node.setId("ocid1.instance.oc1.iad.node1");
        node.setName("oke-cabc-nabc-sabc-0");
        node.setPrivateIp("10.0.10.16");
        node.setAvailabilityDomain("Floc:US-ASHBURN-AD-2");
        node.setFaultDomain("FAULT-DOMAIN-3");
        node.setKubernetesVersion("v1.35.2");
        return node;
    }
}
