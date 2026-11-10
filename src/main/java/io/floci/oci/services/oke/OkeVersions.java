package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Kubernetes versions floci-oci can run, each pinned to the k3s image of the same patch.
 * {@link #SUPPORTED} follows Oracle's "Supported Versions of Kubernetes" table
 * (contengaboutk8sversions.htm) as of 2026-11-10: the three supported minors at their current
 * patches, and the {@code .0} preview versions still in support. {@link #LEGACY} keeps clusters
 * stored by an older floci-oci running on the image they were created with, without advertising it.
 */
final class OkeVersions {

    record Version(String name, String k3sTag, boolean preview) {
        int minor() {
            return OkeVersions.minor(name);
        }
    }

    static final List<Version> SUPPORTED = List.of(
            new Version("v1.34.10", "v1.34.10-k3s1", false),
            new Version("v1.35.0", "v1.35.0-k3s1", true),
            new Version("v1.35.2", "v1.35.2-k3s1", false),
            new Version("v1.36.4", "v1.36.4-k3s1", false),
            new Version("v1.37.0", "v1.37.0-k3s1", true));

    static final List<Version> LEGACY = List.of(
            new Version("v1.30.1", "v1.30.1-k3s1", false));

    /** The newest generally available version. */
    static final String DEFAULT_VERSION = "v1.36.4";

    /**
     * From Kubernetes 1.28, OKE lets the control plane run up to three minor versions ahead of the
     * worker nodes (contengaboutk8sversions.htm, following the upstream version skew policy).
     */
    static final int MAX_NODE_SKEW = 3;

    private OkeVersions() {
    }

    static Optional<Version> supported(String name) {
        return SUPPORTED.stream().filter(v -> v.name().equals(name)).findFirst();
    }

    /** A supported version, or a legacy one already stored on a cluster or node pool. */
    static Optional<Version> runnable(String name) {
        return supported(name).or(() -> LEGACY.stream().filter(v -> v.name().equals(name)).findFirst());
    }

    /**
     * The k3s image of a runnable version. A cluster or node pool stored by an older floci-oci,
     * which accepted any version string, keeps the one image every cluster ran on then.
     */
    static String image(String repository, String name) {
        Version version = name == null ? LEGACY.getFirst() : runnable(name).orElse(LEGACY.getFirst());
        return repository + ":" + version.k3sTag();
    }

    /**
     * The versions GetClusterOptions and GetNodePoolOptions list: the newest patch of each minor
     * plus the preview versions, or every supported patch with {@code shouldListAllPatchVersions}.
     */
    static List<String> advertised(boolean allPatches) {
        Map<Integer, Version> newestPerMinor = new LinkedHashMap<>();
        for (Version version : SUPPORTED) {
            if (!version.preview()) {
                newestPerMinor.merge(version.minor(), version,
                        (a, b) -> patch(a.name()) >= patch(b.name()) ? a : b);
            }
        }
        List<String> names = new ArrayList<>();
        for (Version version : SUPPORTED) {
            if (allPatches || version.preview() || newestPerMinor.get(version.minor()) == version) {
                names.add(version.name());
            }
        }
        return names;
    }

    /**
     * {@code availableKubernetesUpgrades}: the supported versions newer than {@code current} up to
     * the next minor. "Kubernetes requires that you upgrade control plane nodes one minor version at
     * a time" (contengaboutupgradingclusters.htm).
     */
    static List<String> availableUpgrades(String current) {
        if (current == null || !current.matches("v?\\d+\\.\\d+\\.\\d+")) {
            return List.of();
        }
        List<String> upgrades = new ArrayList<>();
        for (Version version : SUPPORTED) {
            if (compare(version.name(), current) > 0 && version.minor() <= minor(current) + 1) {
                upgrades.add(version.name());
            }
        }
        return upgrades;
    }

    /** CreateCluster: the version must be one GetClusterOptions lists. */
    static String requireForNewCluster(String name) {
        if (name == null || name.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: kubernetesVersion");
        }
        if (supported(name).isEmpty()) {
            throw OciException.invalidParameter("Invalid kubernetesVersion: " + name
                    + ". Supported versions: " + String.join(", ", names()));
        }
        return name;
    }

    /** UpdateCluster: no downgrade, no skipped minor, and every node pool within the skew policy. */
    static void requireClusterUpgrade(String current, String target, List<String> nodePoolVersions) {
        if (supported(target).isEmpty()) {
            throw OciException.invalidParameter("Invalid kubernetesVersion: " + target
                    + ". Supported versions: " + String.join(", ", names()));
        }
        if (compare(target, current) < 0) {
            throw OciException.invalidParameter("Cannot downgrade the cluster from " + current + " to " + target);
        }
        if (minor(target) > minor(current) + 1) {
            throw OciException.invalidParameter("Cannot upgrade the cluster from " + current + " to " + target
                    + ": upgrade the control plane one minor version at a time");
        }
        for (String poolVersion : nodePoolVersions) {
            if (minor(target) - minor(poolVersion) > MAX_NODE_SKEW) {
                throw OciException.invalidParameter("Cannot upgrade the cluster to " + target
                        + ": a node pool runs " + poolVersion + ", more than " + MAX_NODE_SKEW
                        + " minor versions behind");
            }
        }
    }

    /** A node pool version: runnable, not newer than the control plane and within the skew policy. */
    static void requireNodePoolVersion(String clusterVersion, String poolVersion) {
        if (runnable(poolVersion).isEmpty()
                || (supported(poolVersion).isEmpty() && !poolVersion.equals(clusterVersion))) {
            throw OciException.invalidParameter("Invalid kubernetesVersion: " + poolVersion
                    + ". Supported versions: " + String.join(", ", names()));
        }
        if (compare(poolVersion, clusterVersion) > 0) {
            throw OciException.invalidParameter("Node pool version " + poolVersion
                    + " is newer than the cluster version " + clusterVersion);
        }
        if (minor(clusterVersion) - minor(poolVersion) > MAX_NODE_SKEW) {
            throw OciException.invalidParameter("Node pool version " + poolVersion + " is more than "
                    + MAX_NODE_SKEW + " minor versions behind the cluster version " + clusterVersion);
        }
    }

    /** The {@code v1.<minor>} part, the form of the {@code node.info/kubeletVersion} node label. */
    static String minorName(String name) {
        return "v1." + minor(name);
    }

    static int minor(String name) {
        return Integer.parseInt(parts(name)[1]);
    }

    private static int patch(String name) {
        return Integer.parseInt(parts(name)[2]);
    }

    static int compare(String a, String b) {
        String[] left = parts(a);
        String[] right = parts(b);
        for (int i = 0; i < 3; i++) {
            int diff = Integer.compare(Integer.parseInt(left[i]), Integer.parseInt(right[i]));
            if (diff != 0) {
                return diff;
            }
        }
        return 0;
    }

    private static String[] parts(String name) {
        if (name == null || !name.matches("v?\\d+\\.\\d+\\.\\d+")) {
            throw OciException.invalidParameter("Invalid kubernetesVersion: " + name);
        }
        return name.replaceFirst("^v", "").split("\\.");
    }

    private static List<String> names() {
        return SUPPORTED.stream().map(Version::name).toList();
    }
}
