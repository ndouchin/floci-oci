# Container Engine for Kubernetes (OKE)

The **Oracle Cloud Infrastructure (OCI) Container Engine for Kubernetes (OKE)** emulator service provides cluster, node pool and cluster add-on lifecycle management, kubeconfig generation, and, in real mode, a local Kubernetes cluster per OKE cluster: a k3s control plane plus one k3s agent container per worker node.

## Service / API / SDK Client

| Area | API Endpoint | SDK Client |
|---|---|---|
| OKE Clusters | `/20180222/clusters` | `ContainerEngineClient` |
| OKE Cluster Add-ons | `/20180222/clusters/{clusterId}/addons`, `/20180222/addonOptions` | `ContainerEngineClient` |
| OKE Node Pools | `/20180222/nodePools` | `ContainerEngineClient` |
| OKE Options | `/20180222/clusterOptions/{clusterOptionId}`, `/20180222/nodePoolOptions/{nodePoolOptionId}` | `ContainerEngineClient` |
| OKE Kubeconfig | `/20180222/clusters/{clusterId}/kubeconfig/content` | `ContainerEngineClient` |
| OKE Work Requests | `/20180222/workRequests` | `ContainerEngineClient` |

## Supported Operations

| Area | Operations |
|---|---|
| Clusters | `CreateCluster`, `GetCluster`, `ListClusters`, `UpdateCluster`, `DeleteCluster`, `UpdateClusterEndpointConfig`, `ClusterMigrateToNativeVcn`, `CreateKubeconfig` |
| Cluster Add-ons | `InstallAddon`, `GetAddon`, `ListAddons`, `UpdateAddon`, `DisableAddon`, `ListAddonOptions` |
| Node Pools | `CreateNodePool`, `GetNodePool`, `ListNodePools`, `UpdateNodePool`, `DeleteNodePool` |
| Options | `GetClusterOptions`, `GetNodePoolOptions` |
| Work Requests | `GetWorkRequest`, `ListWorkRequests`, `ListWorkRequestErrors`, `ListWorkRequestLogs` |

## Kubernetes Versions

floci-oci offers the versions OKE supports, as listed in Oracle's release calendar on 2026-11-10 ([Supported Versions of Kubernetes][versions]), each pinned to the k3s image of the same patch (`<image-repository>:v<version>-k3s1`, `image-repository` defaults to `rancher/k3s`):

| Version | k3s image | OKE status |
|---|---|---|
| `v1.34.10` | `rancher/k3s:v1.34.10-k3s1` | supported |
| `v1.35.0` | `rancher/k3s:v1.35.0-k3s1` | preview |
| `v1.35.2` | `rancher/k3s:v1.35.2-k3s1` | supported |
| `v1.36.4` | `rancher/k3s:v1.36.4-k3s1` | supported, newest |
| `v1.37.0` | `rancher/k3s:v1.37.0-k3s1` | preview |

- **Creating a cluster**: `kubernetesVersion` is required, as in the SDK (`400 MissingParameter` without it) and must be one of the versions above (`400 InvalidParameter` otherwise). Versions OKE has retired, such as the `v1.28.2` and `v1.29.1` older floci-oci builds advertised, are refused.
- **Options**: `GetClusterOptions` and `GetNodePoolOptions` with `all` list the newest patch of each minor plus the previews; `shouldListAllPatchVersions=true` lists every patch. With a cluster OCID, cluster options list the cluster's version and the versions it can be upgraded to, and node pool options list the versions a node pool of that cluster may run. `nodePoolOptions` also lists the shapes in the catalog below.
- **Upgrading the control plane**: `UpdateCluster` with a newer `kubernetesVersion` recreates the k3s server on the new image, on the same volume, so the cluster keeps its data, its CA and its nodes. The cluster is `UPDATING` with an `IN_PROGRESS` `CLUSTER_UPDATE` work request until the new server answers `/readyz`, then `ACTIVE`. "Kubernetes requires that you upgrade control plane nodes one minor version at a time", there is no downgrade, and every node pool version and node version must stay within three minor versions ([Kubernetes Versions and Kubernetes Engine][upgrade-overview], [Upgrading the control plane][upgrade-cp]); any other target is `400 InvalidParameter`. `availableKubernetesUpgrades` lists the supported versions newer than the cluster's, up to the next minor. Worker nodes are not upgraded with the control plane.
- **Node pool versions**: a node pool's `kubernetesVersion` (default: the cluster's) must not be newer than the control plane nor more than three minor versions behind it ("The worker nodes in a cluster must not run a more recent version of Kubernetes than the associated control plane nodes", [upgrade overview][upgrade-overview]). "Changing the Kubernetes version configured for a managed node pool does not change the Kubernetes version running on existing managed nodes" ([same page][upgrade-overview]): existing nodes keep theirs and new nodes take the pool's.
- **Node cycling**: with `nodePoolCyclingDetails.isNodeCyclingEnabled`, a version change replaces the nodes ([Upgrading worker nodes][upgrade-nodes]). `cycleModes` `INSTANCE_REPLACE` (floci-oci's default, as the SDK states none) gives every node a new instance, OCID and address; `BOOT_VOLUME_REPLACE` keeps each node's instance, address and name and restarts it on the new version. floci-oci replaces all nodes at once rather than `maximumSurge` and `maximumUnavailable` at a time, and does not cordon or drain them first.
- **Clusters stored by an older floci-oci** on a version it no longer offers keep running on the image they ran on before (`rancher/k3s:v1.30.1-k3s1`), without that version being advertised.

## Real-Mode Runtime

When real mode is enabled (`FLOCI_OCI_SERVICES_OKE_MOCK=false`, the default outside tests), each cluster is a set of Docker containers on its own Docker network.

### Control plane

- **Agentless k3s server**: the server runs `k3s server --disable-agent --egress-selector-mode=cluster`, so it runs no kubelet and reaches the kubelets and pods through the agents' tunnels. `kubectl get nodes` lists only the worker nodes, and answers "No resources found" before the first node pool, as on OKE ([Oracle's vLLM on OKE tutorial][vllm] shows both). `traefik`, `local-storage` and `metrics-server` are disabled (see Storage and Add-ons).
- **Cluster network**: the server and its agents share the Docker network `floci-oci-oke-net-<cluster OCID>`, created with the cluster and removed on delete, with a `10.0.X.0/24` subnet starting at `10.0.10.0/24` (the worker subnet of OKE's quick-create workflow), skipping subnets other Docker networks hold or that overlap the cluster's pod or service network. Agents find the server by container name on it. The server is also attached to floci-oci's own network (or `FLOCI_OCI_SERVICES_DOCKER_NETWORK`), so the token webhook and in-Docker clients keep working.
- **Lifecycle**: `CreateCluster` returns the cluster `CREATING` with an `IN_PROGRESS` work request. floci-oci polls the k3s API in the background; once `/readyz` answers, the cluster becomes `ACTIVE` and the work request `SUCCEEDED`, whatever the number of nodes. This is what Terraform and `oci ce cluster create --wait-for-state` wait on. A server that exits, or an API that is not ready within `FLOCI_OCI_SERVICES_OKE_READY_TIMEOUT_SECONDS` (default 300), marks both `FAILED`.
- **Dynamic Port Allocation**: Each cluster's Kubernetes API server is bound to a dynamic host port in the configured range (default: `6443..6543`). The port is published on all host interfaces, so the API is reachable from other machines that can reach the host.
- **Reaching the API from other containers**: by default (`FLOCI_OCI_SERVICES_OKE_ENDPOINT_MODE=host`) `endpoints.kubernetes` and the kubeconfig `server` are `https://127.0.0.1:<hostPort>`, which a client in another container cannot reach. Set `FLOCI_OCI_SERVICES_OKE_ENDPOINT_MODE=network` when floci-oci runs in a container (as in `docker-compose.yml`) and the clients are containers on the same Docker network: the address becomes `https://<sidecar container name>:6443`, for example `https://floci-oci-oke-ocid1.cluster.oc1.iad.<unique>:6443`. Every sidecar's serving certificate carries its container name as a SAN, so TLS still verifies against the kubeconfig CA. When floci-oci runs on the host, `network` falls back to `host`, as floci-aws EKS does. It also falls back, with a warning in the log, when the sidecar's network is Docker's default `bridge` (or `host`, `none`), since those networks have no container-name lookup: run floci-oci on a user-defined network, as `docker-compose.yml` does, or set `FLOCI_OCI_SERVICES_DOCKER_NETWORK` to one. The container name contains dots and can have a label that starts with a digit: kubectl and other Go clients accept it, but Java's `java.net.URI` does not parse it as a host.

### Node pools as nodes

- **One k3s agent per node**: every node of a node pool is a k3s agent container (`floci-oci-oke-node-<instance OCID>`) at a fixed address of the cluster network, from `.16` up, with its own named volume. A node pool is `CREATING` (or `UPDATING` after a change that adds or replaces nodes) with an `IN_PROGRESS` work request until all its nodes are Ready, then `ACTIVE`; a pool with no node is `ACTIVE` at once. Nodes not Ready within the ready timeout make the pool `FAILED`.
- **How many nodes, and where**: `nodeConfigDetails.size` spread over `nodeConfigDetails.placementConfigs` in turn, else the deprecated `quantityPerSubnet` nodes in each of `subnetIds` (oci-go-sdk `CreateNodePoolDetails`). Nodes go to the placement's `faultDomains` in turn, else to `FAULT-DOMAIN-1` to `-3` of their availability domain in turn ("Each availability domain has three fault domains", [Supported Labels][labels]). `UpdateNodePool` with a new size, placement or subnet list adds or removes nodes; a smaller pool removes its newest nodes first. Removing a node stops its agent, deletes its Kubernetes Node object and its volume.
- **Node cap**: at most `FLOCI_OCI_SERVICES_OKE_MAX_NODES_PER_CLUSTER` (default 5) agents run per cluster, given to node pools in creation order. Nodes past the cap still appear in `GetNodePool` as `ACTIVE`, with a `lifecycleDetails` note and no Kubernetes node, and a WARN names the cap and the pools short of nodes. The API always reports the requested counts.
- **Shapes and sizing**: each agent container is limited to its shape's CPUs and memory (at most four fifths of the Docker host's memory and all its CPUs), and the kubelet reserves the rest of the host, so a node's allocatable resources come close to its shape. Fixed shapes take Oracle's sizes; flexible shapes take `nodeShapeConfig.ocpus` and `memoryInGBs` within the shape's limits, where memory runs from "1 GB or a value matching the number of OCPUs, whichever is greater" to "64 GB per OCPU" up to the shape's maximum ([Compute Shapes][shapes]); out-of-range values are `400 InvalidParameter`. An x86 OCPU is two vCPUs, an Ampere A1 OCPU one. Oracle does not document OKE's defaults when `nodeShapeConfig` is partial: floci-oci uses 1 OCPU and 16 GB per OCPU (6 GB on `VM.Standard.A1.Flex`). A shape outside the catalog is accepted and sized as 1 OCPU with 16 GB.

| Shapes | Sizes ([Compute Shapes][shapes]) |
|---|---|
| Fixed | `VM.Standard2.1`/`.2`/`.4`/`.8`, `VM.Standard.E2.1`, `VM.Standard.E2.2`, `VM.Standard.E2.1.Micro` |
| Flexible | `VM.Standard.E3.Flex`, `VM.Standard.E4.Flex` (64 OCPUs, 1024 GB), `VM.Standard.E5.Flex` (126 OCPUs, 1049 GB), `VM.Standard3.Flex` (32, 512), `VM.Optimized3.Flex` (18, 256), `VM.Standard.A1.Flex` (76, 472), `VM.Standard.A2.Flex` (78, 946) |
| GPU | `VM.GPU2.1`, `VM.GPU3.1`/`.2`/`.4`, `VM.GPU.A10.1`/`.2`, `BM.GPU2.2`, `BM.GPU3.8`, `BM.GPU4.8`, `BM.GPU.A10.4`, `BM.GPU.A100-v2.8`, `BM.GPU.H100.8`, `BM.GPU.H200.8`, `BM.GPU.L40S.4`, `BM.GPU.B200.8` |

- **GPU shapes**: a node of a GPU shape registers with the taint OKE GPU nodes carry, `nvidia.com/gpu=present:NoSchedule` ("OKE GPU nodes have a `nvidia.com/gpu=present:NoSchedule` taint", [vLLM on OKE][vllm]), and advertises the shape's GPU count as `nvidia.com/gpu` capacity and allocatable, so a pod that requests GPUs and tolerates the taint schedules on it. On OKE the NVIDIA device plugin advertises the GPUs; here floci-oci sets the capacity on the node status, there is no GPU and no device plugin, and a container that needs a real GPU will not find one.

### Node names, labels and provider ID

| What | Value | Source |
|---|---|---|
| Kubernetes node name | the node's private IP, for example `10.0.10.16` | `kubectl get nodes` output in [vLLM on OKE][vllm] (`10.0.10.x`) and the `kubectl label nodes 10.0.1.2` example in [Supported Labels][labels] |
| Node `name` in the API (compute instance name) | `oke-c<cluster>-n<node pool>-s<subnet>-<slot>`, ten characters of each OCID | [Generated Worker Node Name Formats][names] (`oke-cywiqripuyg-nsgagklgnst-st2qczvnmba-0`) |
| `spec.providerID` | `oci://<instance OCID>` | oracle/oci-cloud-controller-manager `providerPrefix` |
| `topology.kubernetes.io/zone`, `failure-domain.beta.kubernetes.io/zone` | the availability domain without its tenancy prefix, for example `US-ASHBURN-AD-1` | [Supported Labels][labels]; oci-cloud-controller-manager `mapAvailabilityDomainToFailureDomain` |
| `topology.kubernetes.io/region`, `failure-domain.beta.kubernetes.io/region` | the instance's `region`: `iad` and `phx` for us-ashburn-1 and us-phoenix-1, the region name elsewhere | oci-cloud-controller-manager `GetZoneByProviderID`; oci-go-sdk `core.Instance.Region` |
| `oci.oraclecloud.com/fault-domain` | `FAULT-DOMAIN-1` to `-3` | [Supported Labels][labels] |
| `node.kubernetes.io/instance-type`, `beta.kubernetes.io/instance-type` | the node shape | oci-cloud-controller-manager `InstanceType` returns the instance shape |
| `node-role.kubernetes.io/node` | `""` (ROLES column `node`) | `kubectl get nodes` output in [vLLM on OKE][vllm] |
| `oci.oraclecloud.com/node.info.managed` | `true` | Oracle's [mixed node clusters announcement][mixed] |
| `displayName`, `hostname`, `internal_addr`, `name` (the node pool), `node.info/kubeletVersion` (`v1.<minor>`), `node.info/compartment.id_prefix`/`_suffix` | as named | seen on real OKE nodes in third-party write-ups, not documented by Oracle |
| `initialNodeLabels` | as given | oci-go-sdk `CreateNodePoolDetails`: "added to nodes after they join the Kubernetes cluster". Labels a kubelet may set on itself are passed at registration, the others (for example `*.kubernetes.io/*` outside the kubelet's allowed set) applied through the API once the node joins. An invalid Kubernetes label is `400 InvalidParameter` (floci-oci's choice: Oracle documents no error). |
| Annotation `oci.oraclecloud.com/compartment-id` | the node pool's compartment | oci-cloud-controller-manager `CompartmentIDAnnotation` |
| Taint | `nvidia.com/gpu=present:NoSchedule` on GPU shapes only | [vLLM on OKE][vllm] |

### Storage

k3s starts with `--disable=local-storage`, and every cluster gets the two storage classes OKE defines, from k3s's auto-deploy directory: "Two storage classes are defined by default, `oci-bv` for the CSI volume plugin and `oci` for the FlexVolume plugin", and in clusters created on Kubernetes 1.24 or later "the `oci-bv` storage class is initially set as the default" ([Provisioning PVCs on the Block Volume service][pvc-bv]). Their definitions follow oracle/oci-cloud-controller-manager's `manifests/container-storage-interface/storage-class.yaml` and `manifests/volume-provisioner/storage-class.yaml`:

| StorageClass | Provisioner | Default | Binding mode | Expansion |
|---|---|---|---|---|
| `oci-bv` | `blockvolume.csi.oraclecloud.com` | yes | `WaitForFirstConsumer` | yes |
| `oci` | `oracle.com/oci` | no | `Immediate` | no |

Each class is served by a `local-path-provisioner` instance registered under that provisioner name, so PVCs bind and PVs carry `pv.kubernetes.io/provisioned-by: blockvolume.csi.oraclecloud.com`. `oci-bv` volumes live on the node that first uses them; `oci` volumes bind before any node is chosen, so they live on a volume every agent of the cluster mounts. Not emulated: OKE's 50 GB minimum and rounding of PVC sizes, block volume performance, encryption and the `oci-bv-encrypted` class the CCM manifests also define (OKE does not create it by default).

### Networking

- **Pod and service networks**: from `options.kubernetesNetworkConfig.podsCidr` and `servicesCidr`, defaulting to `10.244.0.0/16` and `10.96.0.0/16` (oci-go-sdk `KubernetesNetworkConfig`: "Optional, defaults to 10.244.0.0/16" and "defaults to 10.96.0.0/16"). Each node takes a `/25` of the pod network: "Kubernetes Engine allocates a /25 CIDR block of network addresses for each worker node in a cluster" ([IPv4 CIDR Blocks and Kubernetes Engine][cidrs]). The cluster DNS service (`kube-dns`) sits at `10.96.5.5` in the default service network, as Oracle's [Cilium on OKE tutorial][cilium] shows; in another service network floci-oci takes the same offset when it fits, else the tenth address.
- **Validation**: both must be IPv4 CIDR blocks between `/8` and `/28` that do not overlap ("must not overlap", [CIDR blocks][cidrs]), and the pod network must hold at least one `/25`; otherwise `400 InvalidParameter`. Oracle documents no error code or size limit for these cases, so both are floci-oci's choice.
- **CNI**: k3s's flannel runs whatever `clusterPodNetworkOptions.cniType` says. `OCI_VCN_IP_NATIVE` (pods taking addresses from a VCN subnet) is stored and echoed but not emulated.

### LoadBalancer Services

On OKE, a Service of type `LoadBalancer` gets an OCI Load Balancer (the default) or, with `oci.oraclecloud.com/load-balancer-type: "nlb"`, an OCI Network Load Balancer, created by OKE's cloud controller manager; "The load balancer may take a few minutes to go from a pending state to being fully operational", and its address appears as the Service's `EXTERNAL-IP`, for example `192.0.2.22` ([Provisioning OCI Load Balancers][lb-services], [Network Load Balancers][nlb-services]). floci-oci emulates that controller for clusters created in real mode (`FLOCI_OCI_SERVICES_OKE_LOAD_BALANCER_MODE=emulated`, the default): k3s's own servicelb is off (`--disable=servicelb`) and floci-oci writes the OCI-shaped address into the Service's status.

- **Which Services**: those of type `LoadBalancer` without `spec.loadBalancerClass`, the ones a cloud controller handles (kubernetes/cloud-provider `wantsLoadBalancer`); a Service with a class is left to whatever implements it.
- **Addresses**: a public load balancer takes an address from `192.0.2.0/24` (`FLOCI_OCI_SERVICES_OKE_LOAD_BALANCER_PUBLIC_CIDR`, RFC 5737's TEST-NET-1, the range of Oracle's own example, never routed), unique across every cluster of the emulator, from `.10` up. An internal one (`service.beta.kubernetes.io/oci-load-balancer-internal: "true"`, or for an NLB `oci-network-load-balancer.oraclecloud.com/internal: "true"`) takes an address of the cluster's node subnet from `.254` down; node addresses go from `.16` up and the two never meet. `spec.loadBalancerIP` (a reserved public IP on OKE) is honored when it is a free address of the public range; `oci-network-load-balancer.oraclecloud.com/assigned-private-ipv4` when it is a free address of the node subnet. Addresses are stored, so they survive a restart.
- **Status** (`loadBalancerToStatus` in oracle/oci-cloud-controller-manager): one `ingress` entry per address the load balancer has. A public OCI Load Balancer has its public address; an internal one its private address; a public NLB has a private and a public address (private first), or only the public one with `oci-network-load-balancer.oraclecloud.com/external-ip-only: "true"`. `oci.oraclecloud.com/ingress-ip-mode: Proxy|VIP` sets `ipMode`.
- **Refused as the CCM refuses them**, with a `SyncLoadBalancerFailed` Warning event and the Service left `<pending>`: UDP on an OCI Load Balancer ("OCI load balancers do not support UDP"; use an NLB), UDP on an NLB whose security list management mode is not `None`, `sessionAffinity` other than `None`, `oci-load-balancer-shape: flexible` without `-shape-flex-min` and `-shape-flex-max`, an invalid boolean or `ingress-ip-mode`, `loadBalancerIP` on an internal load balancer, `assigned-private-ipv4` on an OCI Load Balancer, and an address floci-oci cannot give (outside the range, or taken).
- **Lifecycle, as a cloud controller does it**: the `service.kubernetes.io/load-balancer-cleanup` finalizer is added first, the events `EnsuringLoadBalancer`, `EnsuredLoadBalancer`, `UpdatedLoadBalancer` (backends changed), `UnAvailableLoadBalancer` (no backend), `DeletingLoadBalancer` and `DeletedLoadBalancer` are recorded from `service-controller` with the upstream messages, and when the Service is deleted or stops being a LoadBalancer the address is released, its appliance removed and the finalizer dropped last. `FLOCI_OCI_SERVICES_OKE_LOAD_BALANCER_PROVISIONING_DELAY_SECONDS` (default 0) holds the status back to mimic OKE's "few minutes".
- **Data path**: each load balancer is an appliance container (`floci-oci-oke-lb-<Service UID>`) started from the cluster's own k3s image, with `NET_ADMIN` and IP forwarding but not privileged, that holds the load balancer's addresses and forwards each Service port to the NodePort of the backend nodes, picked at random, masquerading the source. The backends are the Ready nodes of the cluster's node pools not labelled `node.kubernetes.io/exclude-from-external-load-balancers`; with `externalTrafficPolicy: Local`, only those with a ready endpoint of the Service. An internal appliance sits on the cluster network at its address; a public one on the Docker network `floci-oci-oke-lb-public` (created with the first public load balancer, removed with the last) at its public address, joined to the cluster network to reach the nodes. Every appliance also joins floci-oci's own network when floci-oci runs in a container. `loadBalancerSourceRanges` (IPv4) limits who is forwarded.
- **Host ports**: each Service port is also published on the Docker host's `127.0.0.1`, at a free port by default (`FLOCI_OCI_SERVICES_OKE_LOAD_BALANCER_HOST_PORTS=dynamic`), at the Service's own port with `same-as-service` (a free port when it is taken), or not at all with `none`. The ports are written on the Service as the annotation `floci.io/load-balancer-host-ports`, for example `TCP/80=127.0.0.1:32768`, and kept when the appliance is replaced.

| From | Internal address | Public address |
|---|---|---|
| A pod of the cluster | yes (kube-proxy, and the appliance) | yes (kube-proxy) |
| A container on the cluster's network | yes | only when also on `floci-oci-oke-lb-public` |
| A container on floci-oci's network | the appliance by name or its address on that network | same |
| The Docker host (macOS included) | `127.0.0.1:<host port>` | same |
| A Linux Docker host | also the address itself | also the address itself |

- **Modes**: `servicelb` keeps k3s's servicelb, which publishes the node addresses as before; `off` disables it and leaves Services `<pending>`. A cluster keeps the mode it was created with: a cluster stored by an earlier floci-oci, which had servicelb, keeps it when it is restored, so its Services keep their addresses (recreate the cluster to move it).
- **Not emulated**: OCI Load Balancer and Network Load Balancer resources (floci-oci has no Load Balancer service, so nothing appears in the OCI API, and `serviceLbSubnetIds` and `serviceLbConfig` are only echoed), real public addresses, shapes and bandwidth, health checks and backend policies, TLS listeners, security lists and NSGs, `node-label-selector`, client source address preservation (the appliance masquerades), `allocateLoadBalancerNodePorts: false` (no NodePort to forward to; the address is still reported), and IPv6.
- **Not verified**: that a public NLB reports a private and a public address, and in which order (inferred from the CCM code); publishing an appliance's ports on Docker Desktop or colima (verified on Linux Docker only); real provisioning times.

### Cluster add-ons

- **Defaults**: OKE deploys the Kubernetes Metrics Server only as an optional add-on, and "Optional cluster add-ons are not deployed by default" ([Overview of Cluster Add-ons][addons]). So k3s's bundled metrics-server is disabled and `kubectl top` fails until the `KubernetesMetricsServer` add-on is installed.
- **Offered add-ons**: `KubernetesMetricsServer`, which deploys metrics-server (`rancher/mirrored-metrics-server:v0.8.1`, Deployment `metrics-server` in `kube-system`, as on OKE), and `CertManager`, which is recorded but deploys nothing. "To use the Kubernetes Metrics Server as a cluster add-on, you also have to deploy cert-manager", or set the `skipAddonDependenciesCheck` configuration to `true` when cert-manager runs standalone ([Kubernetes Metrics Server as a Cluster Add-on][metrics-addon]). Other OKE add-ons are not offered (`404 NotAuthorizedOrNotFound`).
- **Rules**: "You can only configure cluster add-ons when using enhanced clusters" ([Configuring Cluster Add-ons][addons-config]); on a basic cluster add-ons are `400 InvalidParameter`. A cluster created without `type` is a basic cluster, as OKE creates one through the CLI or API until June 15, 2027 ([OKE service change announcement][service-change]), so add-ons need `type: ENHANCED_CLUSTER`. `InstallAddon` of an installed add-on without `isOverrideExisting` is `409 Conflict`, and the missing dependency `400 InvalidParameter`; Oracle documents neither error, so both are floci-oci's choice. `DisableAddon` needs `isRemoveExistingAddOn`, which with `true` also removes what the add-on deployed. Each operation answers `202` with an `ENABLE_ADDON`, `UPDATE_ADDON` or `DISABLE_ADDON` work request. `ListAddonOptions` needs `kubernetesVersion`. The add-on versions are the components floci-oci deploys, not OKE's.
- **Persistence**: metrics-server's manifest lives on the server's volume, so it survives a control plane upgrade and a restart.

### Restore at startup

In persistent, hybrid or wal storage modes, floci-oci restores every stored cluster at startup: it adopts a surviving server (matched by container name, labels, image and flags) or recreates it on the surviving volume, keeping its CA; when the volume is gone it starts a fresh server and logs that the CA changed. It then adopts each node's agent (matched by labels, image, server address and join token), recreates missing ones, and removes agents no node of the cluster has. Node pools stored before nodes were run get their agents then. Load balancers keep their stored addresses; the first controller pass adopts each appliance that still runs with the same configuration and recreates the others. In memory mode nothing is restored. Clusters stored by an older floci-oci restart on the agentless control plane with OKE's networks and storage classes; their pods move to agent nodes and their existing Services keep their old addresses, as they keep servicelb.

### Authentication

- **kubectl Authentication**: The kubeconfig is the real OKE token version `2.0.0` shape: its user runs `oci ce cluster generate-token --cluster-id <ocid> --region <region>`, and its cluster entry carries the k3s CA, so TLS is verified. k3s does not know these tokens, so it sends each one to floci-oci in a Kubernetes `TokenReview` (a token webhook meant for the k3s sidecar and unauthenticated like every `/_floci-oci` endpoint, at `/_floci-oci/oke/token-webhook/<tenancy>/<cluster>`, the same design as floci-aws EKS). The API server caches each verdict for 30 seconds.
- **What a token is**: `generate-token` signs a bare `GET https://containerengine.<region>.oraclecloud.com/cluster_request/<cluster-ocid>` with the caller's OCI signer over `date (request-target) host`, copies the `authorization` and `date` headers into the URL's query and base64url-encodes the URL (oci-cli [`containerengine_cli_extended.py`][cli-token], oci-python-sdk `signer.py`). The query is not part of what was signed.
- **What every mode checks**: the token must be that URL for this cluster and its region, with an `rsa-sha256` Signature over `date (request-target) host`, a keyId (or, for a session token, a `tenant` claim) in the cluster's tenancy, and a date within 5 minutes of now ("If the client's clock is skewed more than 5 minutes, a 401 (NotAuthenticated) HTTP status code is returned", [Request Signatures][signing]).
- **Signature verification** (`FLOCI_OCI_SERVICES_OKE_TOKEN_VERIFICATION`):

| Token signed with | `known-keys` (default) | `strict` | `off` |
|---|---|---|---|
| an API key uploaded to identity (`UploadApiKey`, Terraform `oci_identity_api_key`) | signature verified against the key; the caller mapped as OKE maps IAM users (below); a bad signature is `401` | same | not verified; cluster-admin |
| another key of a user who has uploaded keys | `401` (OCI would not know the key) | `401` | not verified; cluster-admin |
| a key of a user with no uploaded key | not verified; cluster-admin, as before; a WARN once per key says how to change that | `401` | not verified; cluster-admin |
| `--auth security_token` (`ST$<jwt>` keyId) for a user that exists in identity, with a `jwk` claim | signature verified against the session key in `jwk` (proof the caller holds it), then mapped as OKE does; the JWT's own signature cannot be checked, since floci-oci issues no session tokens | `401` | not verified; cluster-admin |
| any other session token, and instance principal tokens (`ptype: instance`) | not verified; cluster-admin, username the token's `sub` (the instance OCID for an instance principal) | `401` | not verified; cluster-admin |

- **Who a verified caller becomes**: as OKE documents it ("A user's permissions to access clusters comes from the IAM groups ... to which they belong", "You can use OCIDs to map Kubernetes RBAC rolebindings and clusterrolebindings to IAM users and groups", "By default, users are not assigned any Kubernetes RBAC roles", [About Access Control and Kubernetes Engine][access-control]): the username and uid are the user's OCID, and the groups are the OCIDs of the user's IAM groups in the cluster's tenancy, then `system:authenticated`. A caller who holds `manage` on clusters where the cluster is also gets `system:masters` ("Oracle Cloud Infrastructure tenancy administrators already have sufficient privileges, and do not require the cluster-admin clusterrole", same page; `AdministerK8s` needs `CLUSTER_MANAGE`, [Container Engine policy reference][policy-ref]). That holds for a member of the tenancy's group named `Administrators`, or of a group that a policy of the cluster's tenancy lets `manage clusters`, `manage cluster-family` or `manage all-resources` in the cluster's compartment, one above it, or the tenancy. Anyone else has only what RBAC binds to `User <user OCID>` or `Group <group OCID>`, for example `kubectl create rolebinding devs-view --clusterrole=view --group=<group OCID>`. Verified callers also carry the `extra` fields seen on real clusters: `AuthType=[Native]`, `ContextType=[OCI]`, `Principal=[<OCID>]`, `TenancyID=[<OCID>]`, `TokenV2=[true]`.
- **Becoming cluster-admin after uploading a key**: floci-oci's default tenancy has no `Administrators` group (every real tenancy has one; floci-oci creates it only for child tenancies, through `CreateChildTenancy`). So a user who uploads its key goes from cluster-admin to `403` on everything, as a real non-administrator would, until it joins a group named `Administrators` (create it) or one of its groups gets a policy such as `Allow group <name> to manage cluster-family in tenancy`. floci-oci logs this the first time a verified user is not an administrator.
- **How policies are read**: only `Allow <subject> to manage <clusters | cluster-family | all-resources> in <location>` statements count, where the subject is `group <name>[, <name>]`, `group id <ocid>[, id <ocid>]`, `group '<domain>'/'<name>'` (the `Default` domain only), `any-group` or `any-user`, and the location is `tenancy` (in a policy attached to the tenancy), `compartment id <ocid>`, or `compartment <name>[:<name>]` resolved from the compartment the policy is attached to ([Policy Syntax][policy-syntax], [Policies and Compartment Hierarchies][policies]). Group and compartment names match ignoring case. A statement with a `where` clause never grants, nor does any other form floci-oci cannot evaluate (`Endorse`, `Admit`, permission lists, dynamic groups), so a policy can only make floci-oci stricter than OKE, never looser. Policies of other tenancies are never read, as only callers from the cluster's own tenancy are accepted.
- **Static token alternative**: Each cluster also gets a random API token, stored with the cluster and never returned by `CreateCluster`, `GetCluster` or `ListClusters`. Set `FLOCI_OCI_SERVICES_OKE_KUBECONFIG_AUTH=token` to receive it in the kubeconfig instead of the `exec` user, for clients without the oci CLI. It is cluster-admin on that cluster.
- **Prerequisites for kubectl**: the `oci` CLI on `PATH` with a config whose key can sign. With `known-keys`, any key works until it is uploaded; with `strict`, upload it first (`oci iam user api-key upload --user-id <ocid> --key-file <public.pem>`).
- **Not verified against real OKE**: OKE's token webhook is closed source and no Oracle page describes its algorithm. That it is OCI's standard signed-request check on `GET /cluster_request/<cluster>` is an inference from the token's shape; OKE's accepted clock window and whether it caches verdicts are not documented, so the 5 minutes is OCI's generic limit (`generate-token` refreshes after 4). The group list (`system:masters` for `manage clusters` holders, the order of the entries) and the `extra` keys come from third-party `kubectl auth whoami` output, not Oracle documentation. Whether OKE lists dynamic group OCIDs for users, and whether instance principal tokens carry a `jwk` claim, is not known.

### Persistence, mock mode and teardown

- **Storage Persistence**: each server keeps its state in a Docker named volume mounted at `/var/lib/rancher/k3s`, each agent in its own, and the `oci` storage class in a cluster-wide one, all obeying the global storage persistence and `prune-volumes-on-delete` policies.
- **Mock Mode**: Setting `FLOCI_OCI_SERVICES_OKE_MOCK=true` (default in test profiles) bypasses Docker: clusters are `ACTIVE` at once with the same endpoints a real cluster advertises on the default port (`https://127.0.0.1:6443`, private endpoint `10.0.0.10:6443`), node pools list their nodes `ACTIVE`, and add-ons are recorded only.
- **Teardown**: `DELETE` and application shutdown remove the k3s server and agent containers and the cluster network and keep or drop the named volumes by the policy above, so a persistent cluster comes back after a restart. `POST /_floci-oci/state/reset` forgets every cluster and so always removes its volume too, whatever `prune-volumes-on-delete` says. The anonymous volumes the k3s image declares always go with the container.

## Wire & Behavior Notes

- **Kubeconfig Generation**: `POST /20180222/clusters/{id}/kubeconfig/content` accepts the SDK's `CreateClusterKubeconfigContentDetails` (`tokenVersion`, `expiration`, `endpoint`). `expiration` is deprecated in the SDK and ignored. `endpoint` must be one of `LEGACY_KUBERNETES`, `PUBLIC_ENDPOINT`, `PRIVATE_ENDPOINT` or `VCN_HOSTNAME` (any case; otherwise `400 InvalidParameter`); floci-oci has one reachable API address, `https://127.0.0.1:{hostPort}`, so every value resolves to it. Which endpoint real OKE picks when `endpoint` is omitted is not stated in the SDKs or the CLI (both leave it optional with no default), so that case resolves to the same address. Before the cluster is `ACTIVE` (or in mock mode) there is no CA yet, and the kubeconfig falls back to `insecure-skip-tls-verify: true`.
- **Cluster settings read back as sent**: `type`, `endpointConfig`, `options`, `imagePolicyConfig` and `clusterPodNetworkOptions` are stored from `CreateClusterDetails` and returned by `GetCluster` and `ListClusters` exactly as given, reduced to the fields of their SDK types. Nothing is filled in for a setting that was not sent, since the defaults real OKE applies are not documented in the SDKs. Enum values (`type`, `cniType`, `ipFamilies`) are checked (`400 InvalidParameter`). Apart from `options.kubernetesNetworkConfig` (see Networking) and `type` (which gates add-ons), they are management-plane only: the k3s server does not change with them. `systemTags` is always `{}`.
- **Cluster updates**: `UpdateCluster` changes the name, version, tags, `type`, `imagePolicyConfig`, and the options `UpdateClusterOptionsDetails` allows (`admissionControllerOptions`, `persistentVolumeConfig`, `serviceLbConfig`, OpenID Connect); the network options set on create stay. `UpdateClusterEndpointConfig` changes the NSGs, public IP and security attributes and keeps the subnet; `ClusterMigrateToNativeVcn` gives a cluster created without one its `endpointConfig` (what Terraform calls when `endpoint_config` is added later). Both answer `202` with a `CLUSTER_UPDATE` work request. `GetCluster` returns an `etag`, which `if-match` on these three operations checks.
- **Public endpoint**: with `endpointConfig.isPublicIpEnabled` true, `endpoints.publicEndpoint` is the cluster's one reachable address, as host:port.
- **Work Requests**: Asynchronous mutations (such as cluster and node pool creation or deletion) return `202 Accepted` with an `opc-work-request-id` header, pollable via `/20180222/workRequests/{id}`. `CreateCluster` and `CreateNodePool` return no body, as in the SDK: the new resource's OCID is the work request's `resources[0].identifier`. A cluster create that fails (the k3s sidecar exits, or is not ready in time) leaves its reason both in the cluster's `lifecycleDetails` and as an `InternalServerError` entry of `ListWorkRequestErrors`, which is where Terraform reads it.
- **Node pools need an `ACTIVE` cluster**: on a `CREATING` cluster `CreateNodePool` is `409 IncorrectState` (which SDKs retry), and on a `FAILED`, `DELETING` or `DELETED` one it is `409 Conflict`. OCI documents neither case, so both are floci-oci's choice. `GetNodePool` and `ListNodePools` always carry `systemTags` `{}`, as the cluster does, so the oracle/oci provider (9.12 and later) plans no drift after an apply.
- **Node pool fields**: `nodeConfigDetails`, `subnetIds`, `nodeShapeConfig`, `initialNodeLabels`, `nodeSourceDetails`, `sshPublicKey`, `nodeMetadata`, `nodePoolCyclingDetails` and `nodeEvictionNodePoolSettings` are stored as sent, reduced to the fields of their SDK types, and `nodes[]` lists one node per requested node with its OCID, name, availability domain, fault domain, subnet, private IP, Kubernetes version and lifecycle state. `nodeSourceDetails`, `sshPublicKey`, `nodeMetadata` and the eviction settings have no runtime effect.
- **Immutable Container Naming**: Sidecar containers are named using the immutable cluster OCID (`floci-oci-oke-ocid1.cluster.oc1.iad.xxx`), ensuring display name updates via `UpdateCluster` do not affect container lifecycle or teardown.

## What floci-oci decides where Oracle is silent

- How a token is verified (OCI's signed-request check, inferred), the 5-minute window, and which callers are verified in each `token-verification` mode.
- The load balancer address ranges, which end of the node subnet internal load balancers take, and the order of a public NLB's two addresses.

- Error codes for an invalid `initialNodeLabels` entry, an invalid or overlapping `kubernetesNetworkConfig`, a node pool on a cluster that is not `ACTIVE`, an unknown, duplicate or dependent add-on, and add-ons on a basic cluster.
- The default memory per OCPU of a flexible shape when `nodeShapeConfig` sets only the OCPUs, and the size of a shape outside the catalog.
- `INSTANCE_REPLACE` as the default cycle mode, and replacing every node at once.
- The node labels Oracle does not document (`displayName`, `hostname`, `internal_addr`, `name`, `node.info/*`), which part of each OCID goes into a node name (the last ten characters), and the kube-dns address in a non-default service network.
- Which add-on versions `ListAddonOptions` reports.

## Sources

- [Request Signatures](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/signingrequests.htm) and [Required Keys and OCIDs](https://docs.oracle.com/en-us/iaas/Content/API/Concepts/apisigningkey.htm)
- [About Access Control and Kubernetes Engine](https://docs.oracle.com/en-us/iaas/Content/ContEng/Concepts/contengaboutaccesscontrol.htm)
- [Policy Configuration for Cluster Creation and Deployment / Container Engine policy reference](https://docs.oracle.com/en-us/iaas/Content/Identity/Reference/contengpolicyreference.htm)
- [Policy Syntax](https://docs.oracle.com/en-us/iaas/Content/Identity/Concepts/policysyntax.htm) and [How Policies Work](https://docs.oracle.com/en-us/iaas/Content/Identity/Concepts/policies.htm)
- oci-cli `containerengine_cli_extended.py` (how `generate-token` builds the token)
- [Supported Versions of Kubernetes](https://docs.oracle.com/en-us/iaas/Content/ContEng/Concepts/contengaboutk8sversions.htm)
- [Kubernetes Versions and Kubernetes Engine (OKE)](https://docs.oracle.com/en-us/iaas/Content/ContEng/Concepts/contengupgradeoverview.htm)
- [Upgrading the Kubernetes version on control plane nodes](https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengupgradingk8smasternode.htm)
- [Upgrading the Kubernetes version on worker nodes](https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengupgradingk8sworkernode.htm)
- [Generated Worker Node Name Formats](https://docs.oracle.com/en-us/iaas/Content/ContEng/Reference/contenggeneratednodenames.htm)
- [Supported Labels for Different Usecases](https://docs.oracle.com/en-us/iaas/Content/ContEng/Reference/contengsupportedlabelsusecases.htm)
- [Compute Shapes](https://docs.oracle.com/en-us/iaas/Content/Compute/References/computeshapes.htm)
- [Provisioning PVCs on the Block Volume Service](https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengcreatingpersistentvolumeclaim_topic-Provisioning_PVCs_on_BV.htm)
- [IPv4 CIDR Blocks and Kubernetes Engine (OKE)](https://docs.oracle.com/en-us/iaas/Content/ContEng/Concepts/contengcidrblocks.htm)
- [Provisioning OCI Load Balancers for Kubernetes Services of Type LoadBalancer](https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengcreatingloadbalancers-subtopic.htm) and [Provisioning OCI Network Load Balancers](https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengcreatingnetworkloadbalancers.htm)
- oracle/oci-cloud-controller-manager at `93969b5` (`load_balancer_spec.go` annotations and validation, `load_balancer.go loadBalancerToStatus`, `load_balancer_util.go validateProtocols`) and kubernetes/cloud-provider `controllers/service/controller.go` (finalizer, events)
- [Overview of Cluster Add-ons](https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengintroducingclusteraddons.htm)
- [Configuring Cluster Add-ons](https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengconfiguringclusteraddons.htm)
- [Working with the Kubernetes Metrics Server as a Cluster Add-on](https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengworkingwithmetricsserver_cluster-add-on.htm)
- [Deploy the vLLM production stack on OKE](https://docs.oracle.com/en/learn/deploy-vllm-production-stack-oke/index.html)
- [Use Cilium to Provide Networking Services in OKE](https://docs.oracle.com/en/learn/oke-flannel-to-cilium-cni-plugin/index.html)
- [OCI Kubernetes Engine (OKE) Introduces Support for Mixed Node Clusters](https://blogs.oracle.com/cloud-infrastructure/oci-kubernetes-engine-mixed-node-clusters)
- [Service change: type of new cluster defaults to ENHANCED_CLUSTER](https://docs.oracle.com/en-us/iaas/Content/servicechanges.htm)
- oci-go-sdk `containerengine` and `core` (wire shapes, `KubernetesNetworkConfig` defaults, `core.Instance.Region`), oracle/oci-cloud-controller-manager at `93969b5` (provider ID, zone and region, instance type, compartment annotation, storage class manifests).

[versions]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Concepts/contengaboutk8sversions.htm
[signing]: https://docs.oracle.com/en-us/iaas/Content/API/Concepts/signingrequests.htm
[access-control]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Concepts/contengaboutaccesscontrol.htm
[policy-ref]: https://docs.oracle.com/en-us/iaas/Content/Identity/Reference/contengpolicyreference.htm
[policy-syntax]: https://docs.oracle.com/en-us/iaas/Content/Identity/Concepts/policysyntax.htm
[policies]: https://docs.oracle.com/en-us/iaas/Content/Identity/Concepts/policies.htm
[cli-token]: https://github.com/oracle/oci-cli/blob/b9333bdcdcabdd10b8a4ef340ab4ebfc1d5cf5ae/services/container_engine/src/oci_cli_container_engine/containerengine_cli_extended.py#L30-L90
[upgrade-overview]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Concepts/contengupgradeoverview.htm
[upgrade-cp]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengupgradingk8smasternode.htm
[upgrade-nodes]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengupgradingk8sworkernode.htm
[names]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Reference/contenggeneratednodenames.htm
[labels]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Reference/contengsupportedlabelsusecases.htm
[shapes]: https://docs.oracle.com/en-us/iaas/Content/Compute/References/computeshapes.htm
[pvc-bv]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengcreatingpersistentvolumeclaim_topic-Provisioning_PVCs_on_BV.htm
[cidrs]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Concepts/contengcidrblocks.htm
[addons]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengintroducingclusteraddons.htm
[lb-services]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengcreatingloadbalancers-subtopic.htm
[nlb-services]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengcreatingnetworkloadbalancers.htm
[addons-config]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengconfiguringclusteraddons.htm
[metrics-addon]: https://docs.oracle.com/en-us/iaas/Content/ContEng/Tasks/contengworkingwithmetricsserver_cluster-add-on.htm
[vllm]: https://docs.oracle.com/en/learn/deploy-vllm-production-stack-oke/index.html
[cilium]: https://docs.oracle.com/en/learn/oke-flannel-to-cilium-cni-plugin/index.html
[mixed]: https://blogs.oracle.com/cloud-infrastructure/oci-kubernetes-engine-mixed-node-clusters
[service-change]: https://docs.oracle.com/en-us/iaas/Content/servicechanges.htm

## Quickstart

```bash
E="--endpoint http://localhost:4599"
TENANCY=ocid1.tenancy.oc1..flocilocaltenancy0000000000000000000000000000000000000000

# Create a cluster (enhanced, so it takes add-ons)
oci ce cluster create $E --compartment-id "$TENANCY" \
  --name demo-cluster --vcn-id "ocid1.vcn.oc1.iad.demovcn" --kubernetes-version "v1.36.4" --type ENHANCED_CLUSTER

# List clusters
oci ce cluster list $E --compartment-id "$TENANCY"

# Add two worker nodes (real mode: two k3s agents)
oci ce node-pool create $E --compartment-id "$TENANCY" --cluster-id "ocid1.cluster.oc1.iad.demo" \
  --name workers --node-shape VM.Standard.E4.Flex --node-shape-config '{"ocpus": 1, "memoryInGBs": 4}' \
  --size 2 --placement-configs '[{"availabilityDomain": "Floc:US-ASHBURN-AD-1", "subnetId": "ocid1.subnet.oc1.iad.workers"}]'

# Download kubeconfig (once the cluster is ACTIVE, so it carries the CA)
oci ce cluster create-kubeconfig $E --cluster-id "ocid1.cluster.oc1.iad.demo" --file ./kubeconfig

# kubectl runs `oci ce cluster generate-token` for each request; real mode only
KUBECONFIG=./kubeconfig kubectl get nodes

# A LoadBalancer Service gets an OCI-shaped address and a port on 127.0.0.1
KUBECONFIG=./kubeconfig kubectl expose deployment web --type=LoadBalancer --port=80 --target-port=8080
KUBECONFIG=./kubeconfig kubectl get service web \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip} {.metadata.annotations.floci\.io/load-balancer-host-ports}'

# kubectl top needs the metrics server add-on, as on OKE
oci ce cluster install-addon $E --cluster-id "ocid1.cluster.oc1.iad.demo" --addon-name CertManager
oci ce cluster install-addon $E --cluster-id "ocid1.cluster.oc1.iad.demo" --addon-name KubernetesMetricsServer
```
