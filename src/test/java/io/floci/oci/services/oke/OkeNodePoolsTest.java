package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;
import io.floci.oci.services.oke.model.StoredNode;
import io.floci.oci.services.oke.model.StoredNodePool;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OkeNodePoolsTest {

    private static final String CLUSTER = "ocid1.cluster.oc1.iad.aaaaaaaaclusterclusterabcdefghijk";
    private static final String SUBNET_A = "ocid1.subnet.oc1.iad.aaaaaaaasubnetaaaaaaaaaaa";
    private static final String SUBNET_B = "ocid1.subnet.oc1.iad.aaaaaaaasubnetbbbbbbbbbbb";

    private final AtomicInteger instances = new AtomicInteger();
    private final Supplier<String> instanceOcid = () -> "ocid1.instance.oc1.iad.node" + instances.incrementAndGet();

    @Test
    void sizeWinsOverQuantityPerSubnet() {
        StoredNodePool pool = pool();
        pool.setQuantityPerSubnet(3);
        pool.setSubnetIds(List.of(SUBNET_A, SUBNET_B));
        assertEquals(6, OkeNodePools.requestedCount(pool));

        pool.setNodeConfigDetails(Map.of("size", 2, "placementConfigs", List.of()));
        assertEquals(2, OkeNodePools.requestedCount(pool));
    }

    @Test
    void nodesRotateThroughPlacementsWithSlotsPerSubnetAndFaultDomainsPerAvailabilityDomain() {
        StoredNodePool pool = pool();
        pool.setNodeConfigDetails(OkeNodePools.nodeConfigDetails(Map.of("size", 4, "placementConfigs", List.of(
                Map.of("availabilityDomain", "Floc:US-ASHBURN-AD-1", "subnetId", SUBNET_A),
                Map.of("availabilityDomain", "Floc:US-ASHBURN-AD-1", "subnetId", SUBNET_B))), true));

        OkeNodePools.resize(pool, CLUSTER, "10.0.10.0/24", new HashSet<>(), instanceOcid, () -> null);

        List<StoredNode> nodes = pool.getNodes();
        assertEquals(List.of(SUBNET_A, SUBNET_B, SUBNET_A, SUBNET_B), nodes.stream().map(StoredNode::getSubnetId).toList());
        assertEquals("oke-cabcdefghijk-npoolpoolabc-saaaaaaaaaaa-0", nodes.get(0).getName());
        assertEquals("oke-cabcdefghijk-npoolpoolabc-sbbbbbbbbbbb-0", nodes.get(1).getName());
        assertEquals("oke-cabcdefghijk-npoolpoolabc-saaaaaaaaaaa-1", nodes.get(2).getName());
        assertEquals(List.of("FAULT-DOMAIN-1", "FAULT-DOMAIN-2", "FAULT-DOMAIN-3", "FAULT-DOMAIN-1"),
                nodes.stream().map(StoredNode::getFaultDomain).toList());
        assertEquals(List.of("10.0.10.16", "10.0.10.17", "10.0.10.18", "10.0.10.19"),
                nodes.stream().map(StoredNode::getPrivateIp).toList());
    }

    @Test
    void shrinkingRemovesTheNewestNodesAndGrowingReusesTheirSlotsAndAddresses() {
        StoredNodePool pool = pool();
        pool.setNodeConfigDetails(OkeNodePools.nodeConfigDetails(Map.of("size", 3, "placementConfigs", List.of(
                Map.of("availabilityDomain", "Floc:US-ASHBURN-AD-1", "subnetId", SUBNET_A,
                        "faultDomains", List.of("FAULT-DOMAIN-2")))), true));
        Set<Integer> used = new HashSet<>(Set.of(16));
        OkeNodePools.resize(pool, CLUSTER, "10.0.10.0/24", used, instanceOcid, () -> null);
        assertEquals(List.of("10.0.10.17", "10.0.10.18", "10.0.10.19"),
                pool.getNodes().stream().map(StoredNode::getPrivateIp).toList());
        assertEquals(List.of("FAULT-DOMAIN-2", "FAULT-DOMAIN-2", "FAULT-DOMAIN-2"),
                pool.getNodes().stream().map(StoredNode::getFaultDomain).toList());

        pool.getNodeConfigDetails().put("size", 1);
        List<StoredNode> removed = OkeNodePools.resize(pool, CLUSTER, "10.0.10.0/24", used, instanceOcid, () -> null);

        assertEquals(2, removed.size());
        assertEquals("10.0.10.19", removed.get(0).getPrivateIp());
        assertEquals(1, pool.getNodes().size());
        assertEquals("oke-cabcdefghijk-npoolpoolabc-saaaaaaaaaaa-0", pool.getNodes().getFirst().getName());
    }

    @Test
    void nodeConfigDetailsOnCreateNeedsSizeAndPlacements() {
        assertThrows(OciException.class, () -> OkeNodePools.nodeConfigDetails(Map.of("size", 1), true));
        assertThrows(OciException.class, () -> OkeNodePools.nodeConfigDetails(Map.of("placementConfigs", List.of()), true));
        assertThrows(OciException.class, () -> OkeNodePools.nodeConfigDetails(Map.of("size", 1,
                "placementConfigs", List.of(Map.of("subnetId", SUBNET_A))), true));
        assertThrows(OciException.class, () -> OkeNodePools.nodeConfigDetails(Map.of("size", -1,
                "placementConfigs", List.of()), true));
        assertEquals(Map.of("size", 2), OkeNodePools.nodeConfigDetails(Map.of("size", 2, "unknown", true), false));
    }

    private static StoredNodePool pool() {
        StoredNodePool pool = new StoredNodePool();
        pool.setId("ocid1.nodepool.oc1.iad.aaaaaaaapoolpoolpoolabc");
        pool.setKubernetesVersion("v1.35.2");
        return pool;
    }
}
