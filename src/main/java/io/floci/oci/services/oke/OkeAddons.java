package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The cluster add-ons floci-oci offers. Oracle's "Overview of Cluster Add-ons" lists the Kubernetes
 * Metrics Server as an optional add-on, "not deployed by default", installed with the add-on name
 * {@code KubernetesMetricsServer}, which needs the Certificate Manager add-on ({@code CertManager})
 * unless {@code skipAddonDependenciesCheck} is set; add-ons exist only on enhanced clusters.
 * {@code KubernetesMetricsServer} deploys metrics-server; {@code CertManager} is recorded but deploys
 * nothing. Other OKE add-ons are not offered.
 */
final class OkeAddons {

    static final String METRICS_SERVER = "KubernetesMetricsServer";
    static final String CERT_MANAGER = "CertManager";
    static final String SKIP_DEPENDENCIES = "skipAddonDependenciesCheck";

    record Option(String name, String version, String description) {}

    /**
     * Versions are the ones floci-oci deploys: metrics-server v0.8.1, k3s's own image. OKE's add-on
     * version numbers are not documented, so these are not OKE's.
     */
    static final List<Option> OPTIONS = List.of(
            new Option(CERT_MANAGER, "v1.18.2", "Certificate Manager (recorded only: floci-oci deploys nothing)"),
            new Option(METRICS_SERVER, "v0.8.1", "Kubernetes Metrics Server"));

    private OkeAddons() {
    }

    static Option option(String name) {
        return OPTIONS.stream().filter(option -> option.name().equals(name)).findFirst()
                .orElseThrow(() -> OciException.notAuthorizedOrNotFound("Addon not found: " + name));
    }

    static Optional<String> configuration(List<Map<String, Object>> configurations, String key) {
        if (configurations == null) {
            return Optional.empty();
        }
        return configurations.stream()
                .filter(entry -> key.equals(entry.get("key")))
                .map(entry -> String.valueOf(entry.get("value")))
                .findFirst();
    }

    /** {@code AddonOptionSummary} entries for {@code ListAddonOptions}. */
    static List<Map<String, Object>> optionSummaries(String kubernetesVersion, String addonName, Instant timeCreated) {
        return OPTIONS.stream()
                .filter(option -> addonName == null || option.name().equals(addonName))
                .map(option -> {
                    Map<String, Object> version = new LinkedHashMap<>();
                    version.put("status", "ACTIVE");
                    version.put("versionNumber", option.version());
                    version.put("description", option.description());
                    version.put("kubernetesVersionFilters", Map.of(
                            "minimalVersion", OkeVersions.SUPPORTED.getFirst().name(),
                            "exactKubernetesVersions", List.of(kubernetesVersion)));
                    version.put("configurations", List.of());
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("name", option.name());
                    summary.put("lifecycleState", "ACTIVE");
                    summary.put("isEssential", false);
                    summary.put("versions", List.of(version));
                    summary.put("description", option.description());
                    summary.put("freeformTags", Map.of());
                    summary.put("definedTags", Map.of());
                    summary.put("systemTags", Map.of());
                    summary.put("timeCreated", timeCreated);
                    return summary;
                })
                .toList();
    }
}
