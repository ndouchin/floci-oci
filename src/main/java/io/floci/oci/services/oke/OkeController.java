package io.floci.oci.services.oke;

import io.floci.oci.core.common.OciException;
import io.floci.oci.core.common.OciPage;
import io.floci.oci.core.workrequest.StoredWorkRequest;
import io.floci.oci.core.workrequest.WorkRequestService;
import io.floci.oci.services.identity.CompartmentValidator;
import io.floci.oci.services.oke.model.StoredNodePool;
import io.floci.oci.services.oke.model.StoredOkeCluster;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JAX-RS REST Controller for OCI Container Engine for Kubernetes (OKE) API (version 20180222).
 */
@Path("/20180222")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OkeController {

    private static final String ALL_OPTIONS = "all";
    private static final Instant ADDON_CATALOG_TIME = Instant.parse("2026-01-01T00:00:00Z");
    static final List<String> KUBECONFIG_ENDPOINTS =
            List.of("LEGACY_KUBERNETES", "PUBLIC_ENDPOINT", "PRIVATE_ENDPOINT", "VCN_HOSTNAME");

    private final OkeService service;
    private final CompartmentValidator compartments;
    private final OkeKubeconfigGenerator kubeconfigGenerator;
    private final WorkRequestService workRequests;

    @Inject
    public OkeController(OkeService service, OkeKubeconfigGenerator kubeconfigGenerator,
                         WorkRequestService workRequests, CompartmentValidator compartments) {
        this.service = service;
        this.compartments = compartments;
        this.kubeconfigGenerator = kubeconfigGenerator;
        this.workRequests = workRequests;
    }

    // ── Clusters ─────────────────────────────────────────────────────────────

    @POST
    @Path("/clusters")
    public Response createCluster(CreateClusterDetails details) {
        CreateClusterDetails req = details != null ? details : new CreateClusterDetails();
        try (CompartmentValidator.Use ignored = compartments.useInBody(req.compartmentId)) {
            OkeService.CreateClusterResult result = service.createCluster(
                req.compartmentId,
                req.name,
                req.vcnId,
                req.kubernetesVersion,
                req.kmsKeyId,
                req.freeformTags,
                req.definedTags,
                new OkeService.ClusterSettings(req.type, req.endpointConfig, req.options, req.imagePolicyConfig,
                        req.clusterPodNetworkOptions)
            );

            // CreateCluster has no response body: clients find the cluster through the work request.
            return Response.status(Response.Status.ACCEPTED)
                    .header("opc-work-request-id", result.workRequestId())
                    .build();
        }
    }

    @GET
    @Path("/clusters/{clusterId}")
    public Response getCluster(@PathParam("clusterId") String clusterId) {
        StoredOkeCluster cluster = service.getCluster(clusterId);
        return Response.ok(clusterWire(cluster)).header("etag", cluster.getEtag()).build();
    }

    @GET
    @Path("/clusters")
    public Response listClusters(@QueryParam("compartmentId") String compartmentId) {
        compartments.requireInQuery(compartmentId);
        List<Map<String, Object>> clusters = service.listClusters(compartmentId).stream()
                .map(OkeController::clusterWire)
                .toList();
        return Response.ok(clusters).build();
    }

    /** The cluster with the versions it can be upgraded to, which follow from its own version. */
    private static Map<String, Object> clusterWire(StoredOkeCluster cluster) {
        Map<String, Object> wire = cluster.toWire();
        wire.put("availableKubernetesUpgrades", OkeVersions.availableUpgrades(cluster.getKubernetesVersion()));
        return wire;
    }

    @PUT
    @Path("/clusters/{clusterId}")
    public Response updateCluster(@PathParam("clusterId") String clusterId,
                                  @HeaderParam("if-match") String ifMatch,
                                  UpdateClusterDetails details) {
        UpdateClusterDetails req = details != null ? details : new UpdateClusterDetails();
        String workRequestId = service.updateCluster(clusterId, req.name, req.kubernetesVersion, req.freeformTags,
                req.definedTags, new OkeService.ClusterSettings(req.type, null, req.options, req.imagePolicyConfig,
                        null), ifMatch);
        return accepted(workRequestId);
    }

    @POST
    @Path("/clusters/{clusterId}/actions/updateEndpointConfig")
    public Response updateClusterEndpointConfig(@PathParam("clusterId") String clusterId,
                                                @HeaderParam("if-match") String ifMatch,
                                                Map<String, Object> details) {
        return accepted(service.updateClusterEndpointConfig(clusterId, details, ifMatch));
    }

    @POST
    @Path("/clusters/{clusterId}/actions/migrateToNativeVcn")
    public Response clusterMigrateToNativeVcn(@PathParam("clusterId") String clusterId,
                                              @HeaderParam("if-match") String ifMatch,
                                              Map<String, Object> details) {
        return accepted(service.migrateClusterToNativeVcn(clusterId, details, ifMatch));
    }

    private static Response accepted(String workRequestId) {
        return Response.status(Response.Status.ACCEPTED)
                .header("opc-work-request-id", workRequestId)
                .build();
    }

    @DELETE
    @Path("/clusters/{clusterId}")
    public Response deleteCluster(@PathParam("clusterId") String clusterId) {
        String workRequestId = service.deleteCluster(clusterId);
        return Response.status(Response.Status.ACCEPTED)
                .header("opc-work-request-id", workRequestId)
                .build();
    }

    // ── Kubeconfig ───────────────────────────────────────────────────────────

    @POST
    @Path("/clusters/{clusterId}/kubeconfig/content")
    @Produces("application/x-yaml")
    public Response createKubeconfig(@PathParam("clusterId") String clusterId,
                                     CreateKubeconfigDetails details) {
        if (details != null && details.endpoint != null
                && !KUBECONFIG_ENDPOINTS.contains(details.endpoint.toUpperCase(Locale.ROOT))) {
            throw OciException.invalidParameter("Invalid endpoint: " + details.endpoint
                    + ". Allowed values: " + String.join(", ", KUBECONFIG_ENDPOINTS));
        }
        StoredOkeCluster cluster = service.getCluster(clusterId);
        String kubeconfigYaml = kubeconfigGenerator.generateKubeconfig(cluster);
        return Response.ok(kubeconfigYaml)
                .header("Content-Type", "application/x-yaml")
                .build();
    }

    // ── Node Pools ────────────────────────────────────────────────────────────

    @POST
    @Path("/nodePools")
    public Response createNodePool(CreateNodePoolDetails details) {
        CreateNodePoolDetails req = details != null ? details : new CreateNodePoolDetails();
        try (CompartmentValidator.Use ignored = compartments.useInBody(req.compartmentId)) {
            OkeService.CreateNodePoolResult result = service.createNodePool(
                req.compartmentId,
                req.clusterId,
                req.name,
                req.kubernetesVersion,
                req.nodeShape,
                req.quantityPerSubnet,
                req.freeformTags,
                req.definedTags,
                req.settings()
            );

            // CreateNodePool has no response body either.
            return Response.status(Response.Status.ACCEPTED)
                    .header("opc-work-request-id", result.workRequestId())
                    .build();
        }
    }

    @GET
    @Path("/nodePools/{nodePoolId}")
    public Response getNodePool(@PathParam("nodePoolId") String nodePoolId) {
        StoredNodePool pool = service.getNodePool(nodePoolId);
        return Response.ok(pool.toWire()).build();
    }

    @GET
    @Path("/nodePools")
    public Response listNodePools(@QueryParam("compartmentId") String compartmentId,
                                  @QueryParam("clusterId") String clusterId) {
        compartments.requireInQuery(compartmentId);
        List<Map<String, Object>> pools = service.listNodePools(compartmentId, clusterId).stream()
                .map(StoredNodePool::toWire)
                .toList();
        return Response.ok(pools).build();
    }

    @PUT
    @Path("/nodePools/{nodePoolId}")
    public Response updateNodePool(@PathParam("nodePoolId") String nodePoolId, UpdateNodePoolDetails details) {
        UpdateNodePoolDetails req = details != null ? details : new UpdateNodePoolDetails();
        String workRequestId = service.updateNodePool(nodePoolId, req.name, req.kubernetesVersion,
                req.quantityPerSubnet, req.nodeShape, req.freeformTags, req.definedTags, req.settings());
        return Response.status(Response.Status.ACCEPTED)
                .header("opc-work-request-id", workRequestId)
                .build();
    }

    @DELETE
    @Path("/nodePools/{nodePoolId}")
    public Response deleteNodePool(@PathParam("nodePoolId") String nodePoolId) {
        String workRequestId = service.deleteNodePool(nodePoolId);
        return Response.status(Response.Status.ACCEPTED)
                .header("opc-work-request-id", workRequestId)
                .build();
    }

    // ── Add-ons ──────────────────────────────────────────────────────────────

    @POST
    @Path("/clusters/{clusterId}/addons")
    public Response installAddon(@PathParam("clusterId") String clusterId,
                                 @HeaderParam("if-match") String ifMatch,
                                 InstallAddonDetails details) {
        InstallAddonDetails req = details != null ? details : new InstallAddonDetails();
        return accepted(service.installAddon(clusterId, req.addonName, req.version, req.configurations,
                req.isOverrideExisting, ifMatch));
    }

    @GET
    @Path("/clusters/{clusterId}/addons")
    public Response listAddons(@PathParam("clusterId") String clusterId) {
        return Response.ok(service.listAddons(clusterId).stream().map(addon -> {
            Map<String, Object> summary = addon.toWire();
            summary.remove("configurations");
            return summary;
        }).toList()).build();
    }

    @GET
    @Path("/clusters/{clusterId}/addons/{addonName}")
    public Response getAddon(@PathParam("clusterId") String clusterId, @PathParam("addonName") String addonName) {
        return Response.ok(service.getAddon(clusterId, addonName).toWire()).build();
    }

    @PUT
    @Path("/clusters/{clusterId}/addons/{addonName}")
    public Response updateAddon(@PathParam("clusterId") String clusterId, @PathParam("addonName") String addonName,
                                @HeaderParam("if-match") String ifMatch, UpdateAddonDetails details) {
        UpdateAddonDetails req = details != null ? details : new UpdateAddonDetails();
        return accepted(service.updateAddon(clusterId, addonName, req.version, req.configurations, ifMatch));
    }

    @DELETE
    @Path("/clusters/{clusterId}/addons/{addonName}")
    public Response disableAddon(@PathParam("clusterId") String clusterId, @PathParam("addonName") String addonName,
                                 @QueryParam("isRemoveExistingAddOn") Boolean removeExisting,
                                 @HeaderParam("if-match") String ifMatch) {
        return accepted(service.disableAddon(clusterId, addonName, removeExisting, ifMatch));
    }

    /** ListAddonOptions: the add-ons floci-oci offers for a Kubernetes version. */
    @GET
    @Path("/addonOptions")
    public Response listAddonOptions(@QueryParam("kubernetesVersion") String kubernetesVersion,
                                     @QueryParam("addonName") String addonName) {
        if (kubernetesVersion == null || kubernetesVersion.isBlank()) {
            throw OciException.missingParameter("Missing required parameter: kubernetesVersion");
        }
        return Response.ok(OkeAddons.optionSummaries(kubernetesVersion, addonName, ADDON_CATALOG_TIME)).build();
    }

    // ── Options ───────────────────────────────────────────────────────────────

    /**
     * {@code all} lists every version a new cluster can take; a cluster OCID lists that cluster's
     * version and the ones it can be upgraded to.
     */
    @GET
    @Path("/clusterOptions/{clusterOptionId}")
    public Response getClusterOptions(@PathParam("clusterOptionId") String clusterOptionId,
                                      @QueryParam("shouldListAllPatchVersions") Boolean allPatches) {
        List<String> versions;
        if (ALL_OPTIONS.equals(clusterOptionId)) {
            versions = OkeVersions.advertised(Boolean.TRUE.equals(allPatches));
        } else {
            StoredOkeCluster cluster = service.getCluster(clusterOptionId);
            versions = new ArrayList<>();
            versions.add(cluster.getKubernetesVersion());
            versions.addAll(OkeVersions.availableUpgrades(cluster.getKubernetesVersion()));
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("kubernetesVersions", versions);
        options.put("clusterPodNetworkOptions", OkeClusterSettings.CNI_TYPES.stream().sorted()
                .map(cniType -> Map.of("cniType", cniType)).toList());
        return Response.ok(options).build();
    }

    /**
     * {@code all} lists every version a node pool can take; a cluster OCID lists the versions a node
     * pool of that cluster can run: not newer than its control plane, within the skew policy.
     */
    @GET
    @Path("/nodePoolOptions/{nodePoolOptionId}")
    public Response getNodePoolOptions(@PathParam("nodePoolOptionId") String nodePoolOptionId,
                                       @QueryParam("shouldListAllPatchVersions") Boolean allPatches) {
        List<String> versions = OkeVersions.advertised(Boolean.TRUE.equals(allPatches));
        if (!ALL_OPTIONS.equals(nodePoolOptionId)) {
            String clusterVersion = service.getCluster(nodePoolOptionId).getKubernetesVersion();
            versions = versions.stream()
                    .filter(version -> OkeVersions.compare(version, clusterVersion) <= 0
                            && OkeVersions.minor(clusterVersion) - OkeVersions.minor(version) <= OkeVersions.MAX_NODE_SKEW)
                    .toList();
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("kubernetesVersions", versions);
        options.put("shapes", OkeShapes.names());
        return Response.ok(options).build();
    }

    // ── Work Requests ─────────────────────────────────────────────────────────

    @GET
    @Path("/workRequests/{workRequestId}")
    public Response getWorkRequest(@PathParam("workRequestId") String workRequestId) {
        StoredWorkRequest wr = workRequests.get("oke", workRequestId);
        return Response.ok(workRequestWire(wr)).build();
    }

    @GET
    @Path("/workRequests")
    public Response listWorkRequests(@QueryParam("compartmentId") String compartmentId,
                                      @QueryParam("limit") Integer limit,
                                      @QueryParam("page") String page) {
        compartments.requireInQuery(compartmentId);
        OciPage.Page<Map<String, Object>> result = OciPage.paginate(
                workRequests.list("oke", compartmentId).stream()
                        .map(OkeController::workRequestWire).toList(), limit, page);
        Response.ResponseBuilder builder = Response.ok(result.items());
        if (result.hasNextPage()) {
            builder.header(OciPage.OPC_NEXT_PAGE, result.nextPage());
        }
        return builder.build();
    }

    @GET
    @Path("/workRequests/{workRequestId}/errors")
    public Response listWorkRequestErrors(@PathParam("workRequestId") String workRequestId) {
        StoredWorkRequest workRequest = workRequests.get("oke", workRequestId);
        List<Map<String, Object>> errors = workRequest.getErrors().stream().map(error -> {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("code", error.getCode());
            wire.put("message", error.getMessage());
            wire.put("timestamp", workRequest.getTimeFinished());
            return wire;
        }).toList();
        return Response.ok(errors).build();
    }

    /** The OKE WorkRequest has no errors field: they are served by the errors operation. */
    private static Map<String, Object> workRequestWire(StoredWorkRequest workRequest) {
        Map<String, Object> wire = workRequest.toWire();
        wire.remove("errors");
        return wire;
    }

    @GET
    @Path("/workRequests/{workRequestId}/logs")
    public Response listWorkRequestLogs(@PathParam("workRequestId") String workRequestId) {
        workRequests.get("oke", workRequestId);
        return Response.ok(List.of()).build();
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public static class CreateClusterDetails {
        public String compartmentId;
        public String name;
        public String vcnId;
        public String kubernetesVersion;
        public String kmsKeyId;
        public Map<String, String> freeformTags;
        public Map<String, Map<String, Object>> definedTags;
        public String type;
        public Map<String, Object> endpointConfig;
        public Map<String, Object> options;
        public Map<String, Object> imagePolicyConfig;
        public List<Map<String, Object>> clusterPodNetworkOptions;
    }

    /** {@code UpdateClusterDetails}: no endpoint config or pod network options, which cannot change. */
    public static class UpdateClusterDetails {
        public String name;
        public String kubernetesVersion;
        public Map<String, String> freeformTags;
        public Map<String, Map<String, Object>> definedTags;
        public String type;
        public Map<String, Object> options;
        public Map<String, Object> imagePolicyConfig;
    }

    /**
     * {@code CreateClusterKubeconfigContentDetails} (oci-go-sdk containerengine). Every field is
     * optional. {@code expiration} is accepted and ignored: the SDK documents it as "Deprecated.
     * This field is no longer used." floci-oci has a single reachable API address, so every
     * {@code endpoint} value resolves to it.
     */
    public static class CreateKubeconfigDetails {
        public String tokenVersion;
        public Integer expiration;
        public String endpoint;
    }

    /** The node pool fields CreateNodePoolDetails and UpdateNodePoolDetails share. */
    public static class NodePoolDetails {
        public String name;
        public String kubernetesVersion;
        public String nodeShape;
        public Integer quantityPerSubnet;
        public List<String> subnetIds;
        public Map<String, Object> nodeConfigDetails;
        public Map<String, Object> nodeShapeConfig;
        public List<Map<String, Object>> initialNodeLabels;
        public Map<String, Object> nodeSourceDetails;
        public String sshPublicKey;
        public Map<String, String> nodeMetadata;
        public Map<String, Object> nodePoolCyclingDetails;
        public Map<String, Object> nodeEvictionNodePoolSettings;
        public Map<String, String> freeformTags;
        public Map<String, Map<String, Object>> definedTags;

        OkeService.NodePoolSettings settings() {
            return new OkeService.NodePoolSettings(subnetIds, nodeConfigDetails, nodeShapeConfig, initialNodeLabels,
                    nodeSourceDetails, sshPublicKey, nodeMetadata, nodePoolCyclingDetails,
                    nodeEvictionNodePoolSettings);
        }
    }

    /** {@code InstallAddonDetails}. */
    public static class InstallAddonDetails {
        public String addonName;
        public String version;
        public List<Map<String, Object>> configurations;
        public Boolean isOverrideExisting;
    }

    /** {@code UpdateAddonDetails}. */
    public static class UpdateAddonDetails {
        public String version;
        public List<Map<String, Object>> configurations;
    }

    public static class CreateNodePoolDetails extends NodePoolDetails {
        public String compartmentId;
        public String clusterId;
    }

    public static class UpdateNodePoolDetails extends NodePoolDetails {
    }
}
