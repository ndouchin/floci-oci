package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.docker.ContainerLifecycleManager.HostResources;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OkeShapesTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void flexibleShapesTakeTheirSizeFromNodeShapeConfig() {
        assertEquals(new OkeShapes.Size(2, 32, 4, 0),
                OkeShapes.size("VM.Standard.E4.Flex", Map.of("ocpus", 2.0f, "memoryInGBs", 32.0f)));
        assertEquals(new OkeShapes.Size(1, 16, 2, 0), OkeShapes.size("VM.Standard.E4.Flex", null));
        assertEquals(new OkeShapes.Size(4, 24, 4, 0), OkeShapes.size("VM.Standard.A1.Flex", Map.of("ocpus", 4.0f)));
        assertThrows(OciException.class, () -> OkeShapes.size("VM.Standard.E4.Flex", Map.of("ocpus", 65.0f)));
        assertThrows(OciException.class,
                () -> OkeShapes.size("VM.Standard.E4.Flex", Map.of("ocpus", 1.0f, "memoryInGBs", 65.0f)));
    }

    @Test
    void fixedShapesIgnoreNodeShapeConfigAndGpuShapesCountTheirGpus() {
        assertEquals(new OkeShapes.Size(1, 15, 2, 0), OkeShapes.size("VM.Standard2.1", Map.of("ocpus", 8.0f)));
        assertEquals(new OkeShapes.Size(15, 240, 30, 1), OkeShapes.size("VM.GPU.A10.1", null));
        assertEquals(8, OkeShapes.size("BM.GPU.H100.8", null).gpus());
        assertTrue(OkeShapes.isGpu("VM.GPU.A10.2"));
        assertEquals(new OkeShapes.Size(1, 16, 2, 0), OkeShapes.size("VM.Custom.Unknown", null));
    }

    @Test
    void theAgentIsLimitedToItsShapeAndReservesTheRestOfTheHost() {
        OkeNodeCapacity.Limits small = OkeNodeCapacity.calculate(new OkeShapes.Size(1, 2, 2, 0),
                new HostResources(6, 10 * GIB)).orElseThrow();
        assertEquals(2 * GIB, small.memoryBytes());
        assertEquals(2_000_000_000L, small.nanoCpus());
        assertEquals(List.of("--kubelet-arg=system-reserved=cpu=4000m",
                "--kubelet-arg=eviction-hard=memory.available<8292Mi,nodefs.available<10%,nodefs.inodesFree<5%"),
                small.kubeletArgs());

        OkeNodeCapacity.Limits large = OkeNodeCapacity.calculate(new OkeShapes.Size(15, 240, 30, 1),
                new HostResources(6, 10 * GIB)).orElseThrow();
        assertEquals(8192L * 1024 * 1024, large.memoryBytes());
        assertEquals(6_000_000_000L, large.nanoCpus());
        assertTrue(OkeNodeCapacity.calculate(new OkeShapes.Size(1, 2, 2, 0), null).isEmpty());
    }
}
