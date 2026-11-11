package io.floci.oci.services.oke;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.oci.config.EmulatorConfig;
import io.floci.oci.core.common.docker.ContainerStorageHelper;
import io.floci.oci.testing.TestCompartments;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.RestAssuredConfig;
import io.restassured.config.SSLConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real-sidecar lane for OKE: flips {@code mock=false}, starts a real rancher/k3s sidecar, and
 * walks the client path real OKE users take: the cluster goes CREATING to ACTIVE with its
 * CLUSTER_CREATE work request, the kubeconfig carries the k3s CA and an
 * {@code oci ce cluster generate-token} exec user, and a token minted the way that command mints
 * it reaches the Kubernetes API through floci-oci's token webhook. Skips cleanly when no Docker
 * daemon is accessible.
 */
@QuarkusTest
@TestProfile(OkeDockerTest.RealOkeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OkeDockerTest {

    private static String compartment;
    private static final String VCN = "ocid1.vcn.oc1.iad.dockervcn";
    private static final String USER = "ocid1.user.oc1..okedockeruser";
    private static final Duration ACTIVE_TIMEOUT = Duration.ofSeconds(180);
    private static final Duration NODE_POOL_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration KUBE_OBJECT_TIMEOUT = Duration.ofSeconds(180);
    private static final String WORKER_SUBNET = "ocid1.subnet.oc1.iad.aaaaaaaadockerworkers";

    @Inject
    EmulatorConfig config;

    @Inject
    OkeService service;

    @Inject
    DockerClient docker;

    private String clusterId;
    private String nodePoolId;
    private String workRequestId;
    private int apiPort;
    private KeyStore clusterCa;

    @BeforeEach
    void createTestCompartment() {
        if (compartment == null) {
            compartment = TestCompartments.create("okedockercompartment");
        }
    }

    @BeforeAll
    void requireDocker() {
        boolean dockerAvailable = false;
        try {
            Process ping = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            dockerAvailable = ping.waitFor(5, TimeUnit.SECONDS) && ping.exitValue() == 0;
        } catch (Exception ignored) {
            // No docker CLI or engine: dockerAvailable stays false and the class is skipped.
        }
        assumeTrue(dockerAvailable, "Docker engine / Podman machine not responding, skipping real OKE Docker test");
    }

    @Test
    @Order(1)
    void test1_createReturnsCreatingWithAnInProgressWorkRequest() {
        Response created = given()
            .contentType(ContentType.JSON)
            .body(Map.of(
                "compartmentId", compartment,
                "name", "docker-oke-cluster",
                "vcnId", VCN,
                "kubernetesVersion", "v1.35.2",
                "type", "ENHANCED_CLUSTER"
            ))
            .when().post("/20180222/clusters")
            .then()
                .statusCode(202)
                .header("opc-work-request-id", notNullValue())
                .extract().response();
        workRequestId = created.header("opc-work-request-id");
        clusterId = OkeWorkRequests.createdId(workRequestId);
        assertNotNull(clusterId);
        given()
            .when().get("/20180222/clusters/{id}", clusterId)
            .then()
                .statusCode(200)
                .body("name", equalTo("docker-oke-cluster"))
                .body("lifecycleState", equalTo("CREATING"));

        given()
            .when().get("/20180222/workRequests/{id}", workRequestId)
            .then()
                .statusCode(200)
                .body("status", equalTo("IN_PROGRESS"))
                .body("resources[0].identifier", equalTo(clusterId));
    }

    @Test
    @Order(2)
    void test2_clusterBecomesActiveOnceTheK3sApiAnswers() throws InterruptedException {
        Instant deadline = Instant.now().plus(ACTIVE_TIMEOUT);
        String state = null;
        while (Instant.now().isBefore(deadline)) {
            state = given().when().get("/20180222/clusters/{id}", clusterId)
                .then().statusCode(200).extract().path("lifecycleState");
            if (!"CREATING".equals(state)) {
                break;
            }
            Thread.sleep(2000);
        }
        assertEquals("ACTIVE", state, "the k3s sidecar must run `k3s server` and answer /readyz");

        given()
            .when().get("/20180222/workRequests/{id}", workRequestId)
            .then()
                .statusCode(200)
                .body("status", equalTo("SUCCEEDED"))
                .body("timeFinished", notNullValue());
    }

    @Test
    @Order(3)
    void test3_kubeconfigIsExecWithTheClusterCaAndTlsVerifies() throws Exception {
        String kubeconfig = given()
            .contentType(ContentType.JSON)
            .body(Map.of("tokenVersion", "2.0.0"))
            .when().post("/20180222/clusters/{id}/kubeconfig/content", clusterId)
            .then().statusCode(200)
            .extract().asString();
        assertTrue(kubeconfig.contains("command: oci"), kubeconfig);
        assertTrue(kubeconfig.contains("- generate-token"), kubeconfig);

        Matcher server = Pattern.compile("server: (\\S+)").matcher(kubeconfig);
        Matcher ca = Pattern.compile("certificate-authority-data: (\\S+)").matcher(kubeconfig);
        assertTrue(server.find() && ca.find(), "kubeconfig must carry a server and the cluster CA");
        apiPort = URI.create(server.group(1)).getPort();
        clusterCa = trustStore(ca.group(1));

        assertEquals(401, kubeApi("Bearer not-a-token", "/api/v1/namespaces"),
                "TLS must verify against the kubeconfig CA with no relaxed validation");

        String containerName = ContainerStorageHelper.dockerName(config, "oke-" + clusterId);
        List<String> dnsNames = servingCertificateDnsNames();
        assertTrue(dnsNames.contains(containerName),
                () -> "endpoint-mode=network needs the container name in the serving certificate: " + dnsNames);
    }

    /** The DNS SANs of the certificate the API server presents, verified against the cluster CA. */
    private List<String> servingCertificateDnsNames() throws Exception {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(clusterCa);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", apiPort)) {
            socket.startHandshake();
            X509Certificate serving = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            List<String> names = new ArrayList<>();
            for (List<?> san : serving.getSubjectAlternativeNames()) {
                if (Integer.valueOf(2).equals(san.get(0))) {
                    names.add((String) san.get(1));
                }
            }
            return names;
        }
    }

    @Test
    @Order(4)
    void test4_generateTokenTokensReachTheApiThroughTheWebhook() {
        String tenancy = config.defaultTenancyId();
        String token = ClusterTokenMinter.mint(config.defaultRegion(), clusterId, tenancy, USER, Instant.now());
        String otherCluster = ClusterTokenMinter.mint(config.defaultRegion(),
                "ocid1.cluster.oc1.iad.notthiscluster", tenancy, USER, Instant.now());

        assertEquals(200, kubeApi("Bearer " + token, "/api/v1/namespaces"),
                "k3s must send the generate-token token to floci-oci's webhook and accept its verdict");
        assertEquals(401, kubeApi("Bearer " + otherCluster, "/api/v1/namespaces"));
    }

    @Test
    @Order(5)
    void test5_theStaticTokenStillWorks() {
        String staticToken = service.findCluster(config.defaultTenancyId(), clusterId).orElseThrow().getApiToken();

        assertEquals(200, kubeApi("Bearer " + staticToken, "/api/v1/namespaces"));
    }

    @Test
    @Order(6)
    void test6_aRegisteredKeyMapsTheCallerThroughIamAndRbac() throws InterruptedException {
        String user = given().contentType(ContentType.JSON)
            .body(Map.of("name", "oke-docker-dev", "description", "OKE Docker test user"))
            .when().post("/20160918/users").then().statusCode(200).extract().path("id");
        String devs = given().contentType(ContentType.JSON)
            .body(Map.of("name", "oke-docker-devs", "description", "OKE Docker test group"))
            .when().post("/20160918/groups").then().statusCode(200).extract().path("id");
        given().contentType(ContentType.JSON).body(Map.of("userId", user, "groupId", devs))
            .when().post("/20160918/userGroupMemberships").then().statusCode(200);
        given().contentType(ContentType.JSON).body(Map.of("key", ClusterTokenMinter.publicKeyPem()))
            .when().post("/20160918/users/{userId}/apiKeys", user).then().statusCode(200);

        JsonPath self = selfSubjectReview(freshToken(user));
        assertEquals(user, self.getString("status.userInfo.username"));
        List<String> groups = self.getList("status.userInfo.groups");
        assertTrue(groups.contains(devs) && groups.contains("system:authenticated"), groups::toString);
        assertFalse(groups.contains("system:masters"), groups::toString);
        assertEquals(403, kubeApi("Bearer " + freshToken(user), "/api/v1/namespaces/default/pods"),
                "a verified user without manage clusters has only what RBAC grants");

        kubePost("/apis/rbac.authorization.k8s.io/v1/namespaces/default/rolebindings", Map.of(
                "apiVersion", "rbac.authorization.k8s.io/v1", "kind", "RoleBinding",
                "metadata", Map.of("name", "devs-view"),
                "roleRef", Map.of("apiGroup", "rbac.authorization.k8s.io", "kind", "ClusterRole", "name", "view"),
                "subjects", List.of(Map.of("apiGroup", "rbac.authorization.k8s.io", "kind", "Group", "name", devs))));
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        int status = kubeApi("Bearer " + freshToken(user), "/api/v1/namespaces/default/pods");
        while (status != 200 && Instant.now().isBefore(deadline)) {
            status = kubeApi("Bearer " + freshToken(user), "/api/v1/namespaces/default/pods");
        }
        assertEquals(200, status, "a RoleBinding to the IAM group OCID applies");

        String administrators = given().contentType(ContentType.JSON)
            .body(Map.of("name", "Administrators", "description", "Tenancy administrators"))
            .when().post("/20160918/groups").then().statusCode(200).extract().path("id");
        given().contentType(ContentType.JSON).body(Map.of("userId", user, "groupId", administrators))
            .when().post("/20160918/userGroupMemberships").then().statusCode(200);
        assertTrue(selfSubjectReview(freshToken(user)).getList("status.userInfo.groups").contains("system:masters"),
                "tenancy administrators are cluster-admin with no binding");

        assertEquals(401, kubeApi("Bearer " + ClusterTokenMinter.tamperSignature(freshToken(user)),
                "/api/v1/namespaces"));
    }

    /**
     * A token minted in a second no earlier token of this test used: the API server caches webhook
     * verdicts for 30 s by token, and a token signed in the same second is the same string.
     */
    private String freshToken(String user) throws InterruptedException {
        Thread.sleep(1100);
        return ClusterTokenMinter.mint(config.defaultRegion(), clusterId, config.defaultTenancyId(), user, Instant.now());
    }

    private JsonPath selfSubjectReview(String token) {
        return given()
            .config(RestAssuredConfig.config().sslConfig(SSLConfig.sslConfig().trustStore(clusterCa)))
            .baseUri("https://127.0.0.1")
            .port(apiPort)
            .header("Authorization", "Bearer " + token)
            .contentType(ContentType.JSON)
            .body(Map.of("apiVersion", "authentication.k8s.io/v1", "kind", "SelfSubjectReview"))
            .when().post("/apis/authentication.k8s.io/v1/selfsubjectreviews")
            .then().statusCode(201).extract().jsonPath();
    }

    @Test
    @Order(10)
    void test10_aNodePoolRunsOneK3sAgentPerNodeWithOkeNamesLabelsAndProviderIds() throws InterruptedException {
        Map<String, Object> details = new HashMap<>();
        details.put("compartmentId", compartment);
        details.put("clusterId", clusterId);
        details.put("name", "workers");
        details.put("nodeShape", "VM.Standard.E4.Flex");
        details.put("nodeShapeConfig", Map.of("ocpus", 1, "memoryInGBs", 2));
        details.put("initialNodeLabels", List.of(Map.of("key", "team", "value", "payments"),
                Map.of("key", "tier.kubernetes.io/class", "value", "gold")));
        details.put("nodeConfigDetails", Map.of("size", 2, "placementConfigs", List.of(
                Map.of("availabilityDomain", "Floc:US-ASHBURN-AD-1", "subnetId", WORKER_SUBNET))));
        String poolWorkRequest = given().contentType(ContentType.JSON).body(details)
            .when().post("/20180222/nodePools")
            .then().statusCode(202).extract().header("opc-work-request-id");
        nodePoolId = OkeWorkRequests.createdId(poolWorkRequest);
        given().when().get("/20180222/nodePools/{id}", nodePoolId)
            .then().statusCode(200).body("lifecycleState", equalTo("CREATING"));

        awaitNodePool(nodePoolId, "ACTIVE");
        given().when().get("/20180222/workRequests/{id}", poolWorkRequest)
            .then().statusCode(200).body("status", equalTo("SUCCEEDED"));

        List<Map<String, Object>> apiNodes = given().when().get("/20180222/nodePools/{id}", nodePoolId)
            .then().statusCode(200).extract().path("nodes");
        assertEquals(2, apiNodes.size());
        JsonPath kubeNodes = kube("/api/v1/nodes");
        assertEquals(2, kubeNodes.getList("items").size(), "the control plane runs no kubelet: " + kubeNodes.prettify());
        for (Map<String, Object> apiNode : apiNodes) {
            String name = (String) apiNode.get("privateIp");
            assertEquals("ACTIVE", apiNode.get("lifecycleState"));
            JsonPath node = kube("/api/v1/nodes/" + name);
            assertEquals("oci://" + apiNode.get("id"), node.getString("spec.providerID"));
            Map<String, String> labels = node.getMap("metadata.labels");
            assertEquals("VM.Standard.E4.Flex", labels.get("node.kubernetes.io/instance-type"));
            assertEquals("iad", labels.get("topology.kubernetes.io/region"));
            assertEquals("US-ASHBURN-AD-1", labels.get("topology.kubernetes.io/zone"));
            assertEquals(apiNode.get("faultDomain"), labels.get("oci.oraclecloud.com/fault-domain"));
            assertEquals("", labels.get("node-role.kubernetes.io/node"));
            assertEquals("payments", labels.get("team"));
            assertEquals("gold", labels.get("tier.kubernetes.io/class"));
            assertEquals(apiNode.get("name"), labels.get("displayName"));
            assertEquals(compartment, node.getString("metadata.annotations['oci.oraclecloud.com/compartment-id']"));
            assertEquals("True", node.getString("status.conditions.find { it.type == 'Ready' }.status"));
            assertEquals("v1.35.2+k3s1", node.getString("status.nodeInfo.kubeletVersion"));
        }
    }

    @Test
    @Order(11)
    void test11_shrinkingANodePoolDeletesTheNodeAndItsAgent() throws InterruptedException {
        List<Map<String, Object>> before = given().when().get("/20180222/nodePools/{id}", nodePoolId)
            .then().statusCode(200).extract().path("nodes");
        String removedName = (String) before.get(1).get("privateIp");
        String removedContainer = ContainerStorageHelper.dockerName(config, "oke-node-" + before.get(1).get("id"));
        assertTrue(exists(() -> docker.inspectContainerCmd(removedContainer).exec()));

        given().contentType(ContentType.JSON).body(Map.of("nodeConfigDetails", Map.of("size", 1)))
            .when().put("/20180222/nodePools/{id}", nodePoolId)
            .then().statusCode(202);

        awaitNodePool(nodePoolId, "ACTIVE");
        assertFalse(exists(() -> docker.inspectContainerCmd(removedContainer).exec()));
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (kubeStatus("/api/v1/nodes/" + removedName) != 404 && Instant.now().isBefore(deadline)) {
            Thread.sleep(1000);
        }
        assertEquals(404, kubeStatus("/api/v1/nodes/" + removedName));
        assertEquals(1, kube("/api/v1/nodes").getList("items").size());
    }

    @Test
    @Order(15)
    void test15_aGpuShapeNodeAdvertisesNvidiaGpusAndCarriesOkesGpuTaint() throws InterruptedException {
        String gpuWorkRequest = given().contentType(ContentType.JSON)
            .body(Map.of(
                "compartmentId", compartment,
                "clusterId", clusterId,
                "name", "gpus",
                "nodeShape", "VM.GPU.A10.2",
                "nodeConfigDetails", Map.of("size", 1, "placementConfigs", List.of(
                        Map.of("availabilityDomain", "Floc:US-ASHBURN-AD-2", "subnetId", WORKER_SUBNET)))))
            .when().post("/20180222/nodePools")
            .then().statusCode(202).extract().header("opc-work-request-id");
        String gpuPool = OkeWorkRequests.createdId(gpuWorkRequest);
        awaitNodePool(gpuPool, "ACTIVE");
        String gpuNode = given().when().get("/20180222/nodePools/{id}", gpuPool)
            .then().statusCode(200).extract().path("nodes[0].privateIp");

        JsonPath node = kube("/api/v1/nodes/" + gpuNode);
        assertEquals("2", node.getString("status.capacity['nvidia.com/gpu']"));
        assertEquals("2", node.getString("status.allocatable['nvidia.com/gpu']"));
        assertEquals("VM.GPU.A10.2", node.getString("metadata.labels['node.kubernetes.io/instance-type']"));
        List<Map<String, Object>> taints = node.getList("spec.taints");
        assertEquals(List.of(Map.of("key", "nvidia.com/gpu", "value", "present", "effect", "NoSchedule")),
                taints.stream().filter(taint -> "nvidia.com/gpu".equals(taint.get("key"))).toList());

        kubePost("/api/v1/namespaces/default/pods", Map.of(
                "apiVersion", "v1", "kind", "Pod",
                "metadata", Map.of("name", "cuda"),
                "spec", Map.of(
                        "tolerations", List.of(Map.of("key", "nvidia.com/gpu", "operator", "Exists",
                                "effect", "NoSchedule")),
                        "containers", List.of(Map.of("name", "cuda", "image", "rancher/mirrored-library-busybox:1.37.0",
                                "command", List.of("sleep", "3600"),
                                "resources", Map.of("limits", Map.of("nvidia.com/gpu", 1)))))));
        awaitKube("/api/v1/namespaces/default/pods/cuda", "spec.nodeName", gpuNode);
    }

    @Test
    @Order(20)
    void test20_theStorageClassesAreOkesAndVolumesBindOnTheDefaultOne() throws InterruptedException {
        JsonPath classes = kube("/apis/storage.k8s.io/v1/storageclasses");
        assertEquals(List.of("oci", "oci-bv"), classes.getList("items.metadata.name").stream().sorted().toList(),
                "k3s's own local-path class must be gone");
        JsonPath ociBv = kube("/apis/storage.k8s.io/v1/storageclasses/oci-bv");
        assertEquals("blockvolume.csi.oraclecloud.com", ociBv.getString("provisioner"));
        assertEquals("WaitForFirstConsumer", ociBv.getString("volumeBindingMode"));
        assertTrue(ociBv.getBoolean("allowVolumeExpansion"));
        assertEquals("true", ociBv.getString("metadata.annotations['storageclass.kubernetes.io/is-default-class']"));
        JsonPath oci = kube("/apis/storage.k8s.io/v1/storageclasses/oci");
        assertEquals("oracle.com/oci", oci.getString("provisioner"));
        assertEquals("Immediate", oci.getString("volumeBindingMode"));

        kubePost("/api/v1/namespaces/default/persistentvolumeclaims", Map.of(
                "apiVersion", "v1", "kind", "PersistentVolumeClaim",
                "metadata", Map.of("name", "data"),
                "spec", Map.of("accessModes", List.of("ReadWriteOnce"),
                        "resources", Map.of("requests", Map.of("storage", "50Gi")))));
        kubePost("/api/v1/namespaces/default/persistentvolumeclaims", Map.of(
                "apiVersion", "v1", "kind", "PersistentVolumeClaim",
                "metadata", Map.of("name", "legacy"),
                "spec", Map.of("storageClassName", "oci", "accessModes", List.of("ReadWriteOnce"),
                        "resources", Map.of("requests", Map.of("storage", "50Gi")))));
        kubePost("/api/v1/namespaces/default/pods", Map.of(
                "apiVersion", "v1", "kind", "Pod",
                "metadata", Map.of("name", "writer"),
                "spec", Map.of(
                        "containers", List.of(Map.of("name", "writer", "image", "rancher/mirrored-library-busybox:1.37.0",
                                "command", List.of("sh", "-c", "echo ok > /data/ok && sleep 3600"),
                                "volumeMounts", List.of(Map.of("name", "data", "mountPath", "/data")))),
                        "volumes", List.of(Map.of("name", "data",
                                "persistentVolumeClaim", Map.of("claimName", "data"))))));

        awaitKube("/api/v1/namespaces/default/persistentvolumeclaims/legacy", "status.phase", "Bound");
        awaitKube("/api/v1/namespaces/default/persistentvolumeclaims/data", "status.phase", "Bound");
        JsonPath claim = kube("/api/v1/namespaces/default/persistentvolumeclaims/data");
        assertEquals("oci-bv", claim.getString("spec.storageClassName"));
        JsonPath volume = kube("/api/v1/persistentvolumes/" + claim.getString("spec.volumeName"));
        assertEquals("blockvolume.csi.oraclecloud.com",
                volume.getString("metadata.annotations['pv.kubernetes.io/provisioned-by']"));
        awaitKube("/api/v1/namespaces/default/pods/writer", "status.phase", "Running");
    }

    @Test
    @Order(25)
    void test25_podsAndServicesUseOkesDefaultNetworks() {
        assertEquals("10.96.0.1", kube("/api/v1/namespaces/default/services/kubernetes").getString("spec.clusterIP"));
        assertEquals("10.96.5.5", kube("/api/v1/namespaces/kube-system/services/kube-dns").getString("spec.clusterIP"));
        String podIp = kube("/api/v1/namespaces/default/pods/writer").getString("status.podIP");
        assertTrue(podIp.startsWith("10.244."), podIp);
        for (String podCidr : kube("/api/v1/nodes").<String>getList("items.spec.podCIDR")) {
            assertTrue(podCidr.startsWith("10.244.") && podCidr.endsWith("/25"),
                    "OKE gives each node a /25 of the pods network: " + podCidr);
        }
    }

    @Test
    @Order(26)
    void test26_metricsServerRunsOnlyOnceItsAddonIsInstalled() throws InterruptedException {
        assertEquals(404, kubeStatus("/apis/apiregistration.k8s.io/v1/apiservices/v1beta1.metrics.k8s.io"),
                "OKE does not deploy metrics-server by default");

        given().contentType(ContentType.JSON).body(Map.of("addonName", "CertManager"))
            .when().post("/20180222/clusters/{id}/addons", clusterId)
            .then().statusCode(202);
        given().contentType(ContentType.JSON).body(Map.of("addonName", "KubernetesMetricsServer"))
            .when().post("/20180222/clusters/{id}/addons", clusterId)
            .then().statusCode(202);
        Instant deadline = Instant.now().plus(KUBE_OBJECT_TIMEOUT);
        int status = 0;
        while (Instant.now().isBefore(deadline)) {
            status = kubeStatus("/apis/metrics.k8s.io/v1beta1/nodes");
            if (status == 200 && !kube("/apis/metrics.k8s.io/v1beta1/nodes").getList("items").isEmpty()) {
                break;
            }
            Thread.sleep(3000);
        }
        assertEquals(200, status, "kubectl top nodes must work once the add-on is installed");

        given().queryParam("isRemoveExistingAddOn", true)
            .when().delete("/20180222/clusters/{id}/addons/{name}", clusterId, "KubernetesMetricsServer")
            .then().statusCode(202);
        deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (kubeStatus("/apis/apiregistration.k8s.io/v1/apiservices/v1beta1.metrics.k8s.io") != 404
                && Instant.now().isBefore(deadline)) {
            Thread.sleep(2000);
        }
        assertEquals(404, kubeStatus("/apis/apiregistration.k8s.io/v1/apiservices/v1beta1.metrics.k8s.io"));
    }

    @Test
    @Order(30)
    void test30_aControlPlaneUpgradeRecreatesTheServerOnTheNewVersionAndKeepsTheNodes() throws InterruptedException {
        given().contentType(ContentType.JSON).body(Map.of("kubernetesVersion", "v1.36.4"))
            .when().put("/20180222/clusters/{id}", clusterId)
            .then().statusCode(202);
        assertEquals("UPDATING", given().when().get("/20180222/clusters/{id}", clusterId)
            .then().statusCode(200).extract().path("lifecycleState"));

        Instant deadline = Instant.now().plus(ACTIVE_TIMEOUT);
        String state = null;
        while (Instant.now().isBefore(deadline)) {
            state = given().when().get("/20180222/clusters/{id}", clusterId)
                .then().statusCode(200).extract().path("lifecycleState");
            if (!"UPDATING".equals(state)) {
                break;
            }
            Thread.sleep(2000);
        }
        assertEquals("ACTIVE", state);
        assertEquals("v1.36.4+k3s1", kube("/version").getString("gitVersion"));
        assertTrue(kube("/api/v1/nodes").getList("items").size() >= 2, "the nodes survive the upgrade");
        String workerIp = given().when().get("/20180222/nodePools/{id}", nodePoolId)
            .then().statusCode(200).extract().path("nodes[0].privateIp");
        awaitKube("/api/v1/nodes/" + workerIp, "status.conditions.find { it.type == 'Ready' }.status", "True");
        assertEquals("v1.35.2+k3s1", kube("/api/v1/nodes/" + workerIp).getString("status.nodeInfo.kubeletVersion"));
    }

    @Test
    @Order(31)
    void test31_cyclingANodePoolReplacesItsNodesOnTheNewVersion() throws InterruptedException {
        String oldIp = given().when().get("/20180222/nodePools/{id}", nodePoolId)
            .then().statusCode(200).extract().path("nodes[0].id");
        given().contentType(ContentType.JSON)
            .body(Map.of("kubernetesVersion", "v1.36.4",
                    "nodePoolCyclingDetails", Map.of("isNodeCyclingEnabled", true)))
            .when().put("/20180222/nodePools/{id}", nodePoolId)
            .then().statusCode(202);

        awaitNodePool(nodePoolId, "ACTIVE");
        Map<String, Object> node = given().when().get("/20180222/nodePools/{id}", nodePoolId)
            .then().statusCode(200).extract().path("nodes[0]");
        assertNotEquals(oldIp, node.get("id"), "INSTANCE_REPLACE gives the node a new instance");
        assertEquals("v1.36.4", node.get("kubernetesVersion"));
        assertEquals("v1.36.4+k3s1",
                kube("/api/v1/nodes/" + node.get("privateIp")).getString("status.nodeInfo.kubeletVersion"));
    }

    private String publicAddress;

    @Test
    @Order(40)
    void test40_aLoadBalancerServiceGetsAnOciPublicAddressFromTheEmulatedController() throws InterruptedException {
        kubePost("/apis/apps/v1/namespaces/default/deployments", Map.of(
                "apiVersion", "apps/v1", "kind", "Deployment",
                "metadata", Map.of("name", "web"),
                "spec", Map.of(
                        "replicas", 1,
                        "selector", Map.of("matchLabels", Map.of("app", "web")),
                        "template", Map.of(
                                "metadata", Map.of("labels", Map.of("app", "web")),
                                "spec", Map.of("containers", List.of(Map.of(
                                        "name", "web", "image", "rancher/mirrored-library-busybox:1.37.0",
                                        "command", List.of("sh", "-c", "mkdir -p /www && echo ok > /www/index.html"
                                                + " && exec httpd -f -p 8080 -h /www"),
                                        "ports", List.of(Map.of("containerPort", 8080)))))))));
        kubePost("/api/v1/namespaces/default/services", loadBalancerService("web", Map.of()));

        publicAddress = awaitLoadBalancerIp("web", Duration.ofSeconds(30));
        assertTrue(publicAddress.startsWith("192.0.2."), publicAddress);
        JsonPath service = kube("/api/v1/namespaces/default/services/web");
        assertEquals(List.of("service.kubernetes.io/load-balancer-cleanup"), service.getList("metadata.finalizers"));
        assertTrue(service.getString("metadata.annotations['floci.io/load-balancer-host-ports']")
                .matches("TCP/80=127\\.0\\.0\\.1:\\d+"), service.prettify());
        List<String> reasons = given()
            .config(RestAssuredConfig.config().sslConfig(SSLConfig.sslConfig().trustStore(clusterCa)))
            .baseUri("https://127.0.0.1").port(apiPort)
            .header("Authorization", "Bearer " + staticToken())
            .queryParam("fieldSelector", "involvedObject.name=web")
            .when().get("/api/v1/namespaces/default/events")
            .then().statusCode(200).extract().jsonPath().getList("items.reason");
        assertTrue(reasons.contains("EnsuringLoadBalancer") && reasons.contains("EnsuredLoadBalancer"),
                reasons::toString);
        String applianceName = ContainerStorageHelper.dockerName(config, "oke-lb-" + service.getString("metadata.uid"));
        assertTrue(exists(() -> docker.inspectContainerCmd(applianceName).exec()));
    }

    @Test
    @Order(41)
    void test41_theInternalAnnotationGivesAnAddressInTheClusterSubnet() throws InterruptedException {
        kubePost("/api/v1/namespaces/default/services", loadBalancerService("web-internal",
                Map.of("service.beta.kubernetes.io/oci-load-balancer-internal", "true")));

        String internal = awaitLoadBalancerIp("web-internal", Duration.ofSeconds(30));

        String subnet = service.findCluster(config.defaultTenancyId(), clusterId).orElseThrow().getNodeSubnet();
        assertTrue(OkeLoadBalancerAddresses.inCidr(internal, subnet), internal + " in " + subnet);
        assertEquals(subnet.substring(0, subnet.lastIndexOf('.')) + ".254", internal);
    }

    @Test
    @Order(42)
    void test42_theStatusSurvivesAScaleAsServicelbIsOff() throws InterruptedException {
        given()
            .config(RestAssuredConfig.config().sslConfig(SSLConfig.sslConfig().trustStore(clusterCa)))
            .baseUri("https://127.0.0.1").port(apiPort)
            .header("Authorization", "Bearer " + staticToken())
            .contentType("application/merge-patch+json")
            .body(Map.of("spec", Map.of("replicas", 2)))
            .when().patch("/apis/apps/v1/namespaces/default/deployments/web/scale")
            .then().statusCode(200);
        Thread.sleep(10_000);

        assertEquals(publicAddress, kube("/api/v1/namespaces/default/services/web")
                .getString("status.loadBalancer.ingress[0].ip"));
        assertEquals(1, kube("/api/v1/namespaces/default/services/web").getList("status.loadBalancer.ingress").size());
    }

    @Test
    @Order(43)
    void test43_thePublishedHostPortServesTheApp() throws InterruptedException {
        String annotation = kube("/api/v1/namespaces/default/services/web")
                .getString("metadata.annotations['floci.io/load-balancer-host-ports']");
        int hostPort = Integer.parseInt(annotation.substring(annotation.lastIndexOf(':') + 1));
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        int status = 0;
        while (Instant.now().isBefore(deadline)) {
            try {
                status = given().baseUri("http://127.0.0.1").port(hostPort).when().get("/index.html")
                    .then().extract().statusCode();
            } catch (RuntimeException notYet) {
                status = 0;
            }
            if (status == 200) {
                break;
            }
            Thread.sleep(1000);
        }
        assertEquals(200, status, "the appliance forwards its host port to a node's NodePort");
    }

    @Test
    @Order(44)
    void test44_aPodReachesTheLoadBalancerAddress() throws InterruptedException {
        kubePost("/api/v1/namespaces/default/pods", Map.of(
                "apiVersion", "v1", "kind", "Pod",
                "metadata", Map.of("name", "lb-client"),
                "spec", Map.of(
                        "restartPolicy", "Never",
                        "containers", List.of(Map.of("name", "client", "image", "rancher/mirrored-library-busybox:1.37.0",
                                "command", List.of("sh", "-c", "for i in 1 2 3 4 5 6 7 8 9 10; do wget -q -T 5 -O-"
                                        + " http://" + publicAddress + "/index.html | grep -q ok && exit 0; sleep 2;"
                                        + " done; exit 1"))))));

        awaitKube("/api/v1/namespaces/default/pods/lb-client", "status.phase", "Succeeded");
    }

    @Test
    @Order(45)
    void test45_deletingTheServiceRemovesItsApplianceAndFinalizer() throws InterruptedException {
        String uid = kube("/api/v1/namespaces/default/services/web").getString("metadata.uid");
        String appliance = ContainerStorageHelper.dockerName(config, "oke-lb-" + uid);
        given()
            .config(RestAssuredConfig.config().sslConfig(SSLConfig.sslConfig().trustStore(clusterCa)))
            .baseUri("https://127.0.0.1").port(apiPort)
            .header("Authorization", "Bearer " + staticToken())
            .when().delete("/api/v1/namespaces/default/services/web")
            .then().statusCode(200);

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while ((kubeStatus("/api/v1/namespaces/default/services/web") != 404
                || exists(() -> docker.inspectContainerCmd(appliance).exec())) && Instant.now().isBefore(deadline)) {
            Thread.sleep(1000);
        }
        assertEquals(404, kubeStatus("/api/v1/namespaces/default/services/web"),
                "the Service goes once the controller drops its finalizer");
        assertFalse(exists(() -> docker.inspectContainerCmd(appliance).exec()));
        String publicNetwork = ContainerStorageHelper.dockerName(config, "oke-lb-public");
        assertFalse(exists(() -> docker.inspectNetworkCmd().withNetworkId(publicNetwork).exec()),
                "the public network goes with its last appliance");
    }

    private static Map<String, Object> loadBalancerService(String name, Map<String, String> annotations) {
        return Map.of(
                "apiVersion", "v1", "kind", "Service",
                "metadata", Map.of("name", name, "annotations", annotations),
                "spec", Map.of(
                        "type", "LoadBalancer",
                        "selector", Map.of("app", "web"),
                        "ports", List.of(Map.of("name", "http", "port", 80, "targetPort", 8080))));
    }

    private String awaitLoadBalancerIp(String service, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        String ip = null;
        while (Instant.now().isBefore(deadline)) {
            ip = kube("/api/v1/namespaces/default/services/" + service).getString("status.loadBalancer.ingress[0].ip");
            if (ip != null) {
                return ip;
            }
            Thread.sleep(1000);
        }
        assertNotNull(ip, "Service " + service + " got no load balancer address within " + timeout);
        return ip;
    }

    @Test
    @Order(99)
    void test99_deleteRemovesTheSidecarAgentsNetworkAndEveryVolume() throws InterruptedException {
        String container = ContainerStorageHelper.dockerName(config, "oke-" + clusterId);
        String network = ContainerStorageHelper.dockerName(config, "oke-net-" + clusterId);
        List<String> containers = new ArrayList<>(List.of(container));
        docker.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(OkeClusterManager.CLUSTER_LABEL, clusterId)).exec()
                .forEach(c -> containers.add(c.getId()));
        // The named k3s data volumes and the anonymous ones the k3s image declares.
        List<String> volumes = new ArrayList<>();
        for (String name : containers) {
            docker.inspectContainerCmd(name).exec().getMounts().stream()
                    .filter(mount -> mount.getName() != null)
                    .map(InspectContainerResponse.Mount::getName)
                    .forEach(volumes::add);
        }
        assertTrue(volumes.size() > 1, "expected the named volume plus k3s's anonymous ones: " + volumes);
        assertTrue(containers.size() > 1, "expected the server and at least one agent: " + containers);

        given()
            .when().delete("/20180222/clusters/{id}", clusterId)
            .then().statusCode(202);

        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (exists(() -> docker.inspectContainerCmd(container).exec()) && Instant.now().isBefore(deadline)) {
            Thread.sleep(500);
        }
        for (String name : containers) {
            assertFalse(exists(() -> docker.inspectContainerCmd(name).exec()), "container " + name + " is still there");
        }
        assertFalse(exists(() -> docker.inspectNetworkCmd().withNetworkId(network).exec()), "network " + network + " leaked");
        assertTrue(docker.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(OkeClusterManager.CLUSTER_LABEL, clusterId)).exec().isEmpty(),
                "every agent and load balancer appliance goes with the cluster");
        // Memory storage mode, so the named volume goes as well as the anonymous ones.
        for (String volume : volumes) {
            assertFalse(exists(() -> docker.inspectVolumeCmd(volume).exec()), "volume " + volume + " leaked");
        }
    }

    private static boolean exists(Runnable inspect) {
        try {
            inspect.run();
            return true;
        } catch (NotFoundException expected) {
            // Docker answers 404 once the container or volume is gone, which is what we wait for.
            return false;
        }
    }

    private void awaitNodePool(String poolId, String state) throws InterruptedException {
        Instant deadline = Instant.now().plus(NODE_POOL_TIMEOUT);
        String current = null;
        while (Instant.now().isBefore(deadline)) {
            current = given().when().get("/20180222/nodePools/{id}", poolId)
                .then().statusCode(200).extract().path("lifecycleState");
            if (state.equals(current)) {
                return;
            }
            assertNotEquals("FAILED", current, "node pool " + poolId + " failed");
            Thread.sleep(2000);
        }
        assertEquals(state, current, "node pool " + poolId + " did not become " + state);
    }

    private void awaitKube(String path, String field, String expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(KUBE_OBJECT_TIMEOUT);
        String current = null;
        while (Instant.now().isBefore(deadline)) {
            current = kube(path).getString(field);
            if (expected.equals(current)) {
                return;
            }
            Thread.sleep(2000);
        }
        assertEquals(expected, current, path + " " + field);
    }

    private void kubePost(String path, Map<String, Object> body) {
        given()
            .config(RestAssuredConfig.config().sslConfig(SSLConfig.sslConfig().trustStore(clusterCa)))
            .baseUri("https://127.0.0.1")
            .port(apiPort)
            .header("Authorization", "Bearer " + staticToken())
            .contentType(ContentType.JSON)
            .body(body)
            .when().post(path)
            .then().statusCode(201);
    }

    private String staticToken() {
        return service.findCluster(config.defaultTenancyId(), clusterId).orElseThrow().getApiToken();
    }

    private JsonPath kube(String path) {
        return given()
            .config(RestAssuredConfig.config().sslConfig(SSLConfig.sslConfig().trustStore(clusterCa)))
            .baseUri("https://127.0.0.1")
            .port(apiPort)
            .header("Authorization", "Bearer " + staticToken())
            .when().get(path)
            .then().statusCode(200).extract().jsonPath();
    }

    private int kubeStatus(String path) {
        return kubeApi("Bearer " + staticToken(), path);
    }

    private int kubeApi(String bearer, String path) {
        return given()
            .config(RestAssuredConfig.config().sslConfig(SSLConfig.sslConfig().trustStore(clusterCa)))
            .baseUri("https://127.0.0.1")
            .port(apiPort)
            .header("Authorization", bearer)
            .when().get(path)
            .then().extract().statusCode();
    }

    private static KeyStore trustStore(String base64Pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        int index = 0;
        for (Certificate certificate : factory.generateCertificates(
                new ByteArrayInputStream(Base64.getDecoder().decode(base64Pem)))) {
            keyStore.setCertificateEntry("k3s-ca-" + index++, certificate);
        }
        return keyStore;
    }

    /**
     * Real mode, with the test server listening on the same port as {@code floci-oci.port}: k3s
     * calls the token webhook at that port, as it does outside tests, where the two never differ.
     */
    public static class RealOkeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            String port = String.valueOf(freePort());
            return Map.of(
                    "floci-oci.services.oke.mock", "false",
                    "quarkus.http.test-port", port,
                    "floci-oci.port", port);
        }

        private static int freePort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException("No free port for the OKE Docker test", e);
            }
        }
    }
}
