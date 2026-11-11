package io.floci.oci.services.oke;

import io.floci.oci.core.common.docker.ContainerLifecycleManager.HostResources;

import java.util.List;
import java.util.Optional;

/**
 * The Docker limits and kubelet reservations that make a k3s agent look like a node of its shape.
 * A nested kubelet reports the Docker host's CPUs and memory as its capacity, so the container is
 * limited to the shape (at most four fifths of the host's memory and all its CPUs), the CPUs past the
 * shape are reserved for the system, and the memory eviction threshold covers the gap between the
 * host and the container so the kubelet evicts pods before the container's cgroup runs out.
 * Allocatable then comes close to the shape when the shape fits the host.
 */
final class OkeNodeCapacity {

    private static final long MIB = 1024L * 1024L;
    private static final long EVICTION_BUFFER_MIB = 100;

    record Limits(long memoryBytes, long nanoCpus, List<String> kubeletArgs) {}

    private OkeNodeCapacity() {
    }

    static Optional<Limits> calculate(OkeShapes.Size size, HostResources host) {
        if (host == null || host.cpus() <= 0 || host.memoryBytes() <= 0) {
            return Optional.empty();
        }
        long hostMib = host.memoryBytes() / MIB;
        long memoryMib = Math.min((long) (size.memoryGb() * 1024), hostMib * 4 / 5);
        int vcpus = Math.max(1, Math.min(size.vcpus(), host.cpus()));
        int systemCpuMilli = (host.cpus() - vcpus) * 1000;
        long evictionMib = hostMib - memoryMib + EVICTION_BUFFER_MIB;
        return Optional.of(new Limits(memoryMib * MIB, vcpus * 1_000_000_000L, List.of(
                "--kubelet-arg=system-reserved=cpu=" + systemCpuMilli + "m",
                "--kubelet-arg=eviction-hard=memory.available<" + evictionMib
                        + "Mi,nodefs.available<10%,nodefs.inodesFree<5%")));
    }
}
