package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The compute shapes node pools can use, with the OCPUs, memory and GPUs Oracle's "Compute Shapes"
 * reference (Compute/References/computeshapes.htm) gives them. An x86 OCPU is two vCPUs, an Ampere
 * A1 OCPU one; a flexible shape takes its size from {@code nodeShapeConfig} within the shape's limits.
 */
final class OkeShapes {

    /** One shape. Fixed shapes have {@code maxOcpus == 0}. */
    record Shape(String name, double ocpus, double memoryGb, int vcpusPerOcpu, int gpus,
                 int maxOcpus, double maxMemoryGb, double defaultMemoryPerOcpu) {
        boolean flexible() {
            return maxOcpus > 0;
        }
    }

    /** What one node of a pool gets. */
    record Size(double ocpus, double memoryGb, int vcpus, int gpus) {}

    private static final Map<String, Shape> CATALOG = new LinkedHashMap<>();

    static {
        fixed("VM.Standard2.1", 1, 15, 2, 0);
        fixed("VM.Standard2.2", 2, 30, 2, 0);
        fixed("VM.Standard2.4", 4, 60, 2, 0);
        fixed("VM.Standard2.8", 8, 120, 2, 0);
        fixed("VM.Standard.E2.1", 1, 8, 2, 0);
        fixed("VM.Standard.E2.2", 2, 16, 2, 0);
        fixed("VM.Standard.E2.1.Micro", 1, 1, 2, 0);
        flex("VM.Standard.E3.Flex", 64, 1024, 2, 16);
        flex("VM.Standard.E4.Flex", 64, 1024, 2, 16);
        flex("VM.Standard.E5.Flex", 94, 1049, 2, 16);
        flex("VM.Standard3.Flex", 32, 512, 2, 16);
        flex("VM.Optimized3.Flex", 18, 256, 2, 16);
        flex("VM.Standard.A1.Flex", 76, 472, 1, 6);
        flex("VM.Standard.A2.Flex", 78, 946, 2, 16);
        fixed("VM.GPU2.1", 12, 72, 2, 1);
        fixed("VM.GPU3.1", 6, 90, 2, 1);
        fixed("VM.GPU3.2", 12, 180, 2, 2);
        fixed("VM.GPU3.4", 24, 360, 2, 4);
        fixed("VM.GPU.A10.1", 15, 240, 2, 1);
        fixed("VM.GPU.A10.2", 30, 480, 2, 2);
        fixed("BM.GPU2.2", 28, 192, 2, 2);
        fixed("BM.GPU3.8", 52, 768, 2, 8);
        fixed("BM.GPU4.8", 64, 2048, 2, 8);
        fixed("BM.GPU.A10.4", 64, 1024, 2, 4);
        fixed("BM.GPU.A100-v2.8", 128, 2048, 2, 8);
        fixed("BM.GPU.H100.8", 112, 2048, 2, 8);
        fixed("BM.GPU.H200.8", 112, 3072, 2, 8);
        fixed("BM.GPU.L40S.4", 112, 1024, 2, 4);
        fixed("BM.GPU.B200.8", 128, 4096, 2, 8);
    }

    /**
     * A shape floci-oci does not list still makes nodes, sized like one OCPU with 16 GB: the
     * management plane accepts any shape name, as it did before shapes were sized.
     */
    private static final Size UNKNOWN_SHAPE = new Size(1, 16, 2, 0);

    private OkeShapes() {
    }

    private static void fixed(String name, double ocpus, double memoryGb, int vcpusPerOcpu, int gpus) {
        CATALOG.put(name, new Shape(name, ocpus, memoryGb, vcpusPerOcpu, gpus, 0, 0, 0));
    }

    private static void flex(String name, int maxOcpus, double maxMemoryGb, int vcpusPerOcpu,
                             double defaultMemoryPerOcpu) {
        CATALOG.put(name, new Shape(name, 1, defaultMemoryPerOcpu, vcpusPerOcpu, 0, maxOcpus, maxMemoryGb,
                defaultMemoryPerOcpu));
    }

    static List<String> names() {
        return List.copyOf(CATALOG.keySet());
    }

    static boolean isGpu(String shape) {
        Shape known = CATALOG.get(shape);
        return known != null && known.gpus() > 0;
    }

    /**
     * The size of one node of {@code shape}. A flexible shape takes {@code ocpus} and
     * {@code memoryInGBs} from {@code nodeShapeConfig}: one OCPU when unset, and the shape's default
     * memory per OCPU when only the OCPUs are set (Oracle does not document OKE's defaults; see
     * the docs page). Out-of-range values are 400 InvalidParameter: "1 GB minimum; 64 GB per OCPU"
     * up to the shape's maximum. A fixed shape ignores {@code nodeShapeConfig}.
     */
    static Size size(String shape, Map<String, Object> nodeShapeConfig) {
        Shape known = CATALOG.get(shape);
        if (known == null) {
            return UNKNOWN_SHAPE;
        }
        if (!known.flexible()) {
            return new Size(known.ocpus(), known.memoryGb(), vcpus(known, known.ocpus()), known.gpus());
        }
        double ocpus = number(nodeShapeConfig, "ocpus", 1);
        if (ocpus < 1 || ocpus > known.maxOcpus()) {
            throw OciException.invalidParameter("nodeShapeConfig.ocpus for " + shape + " must be between 1 and "
                    + known.maxOcpus());
        }
        double memory = number(nodeShapeConfig, "memoryInGBs", ocpus * known.defaultMemoryPerOcpu());
        double maxMemory = Math.min(64 * ocpus, known.maxMemoryGb());
        if (memory < 1 || memory > maxMemory) {
            throw OciException.invalidParameter("nodeShapeConfig.memoryInGBs for " + shape + " with " + ocpus
                    + " OCPUs must be between 1 and " + maxMemory);
        }
        return new Size(ocpus, memory, vcpus(known, ocpus), 0);
    }

    private static int vcpus(Shape shape, double ocpus) {
        return (int) Math.ceil(ocpus * shape.vcpusPerOcpu());
    }

    private static double number(Map<String, Object> config, String key, double fallback) {
        if (config != null && config.get(key) instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }
}
