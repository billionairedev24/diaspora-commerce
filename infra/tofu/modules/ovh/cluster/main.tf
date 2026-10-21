# OVHcloud Managed Kubernetes Service (MKS), cost first:
#   - control plane: "free" plan (no charge) unless zone_count > 1, which needs the paid "standard" plan (3-AZ control plane)
#   - nodes: B3 general-purpose instances (cheapest x86 line). small = b3-8 (2 vCPU / 8 GB), medium = b3-16 (4/16),
#     large = b3-32 (8/32); lean = one b3-16 and nothing else. OVH has no arm64 instances.
#   - stable pool is billed MONTHLY (about half the hourly price) when deletion_protection is on (prod); hourly otherwise,
#     because a monthly-billed pool cannot be switched back and a trial must be disposable.
#   - "spot": OVH has no spot market, so spot_enabled creates a tainted (afrotransact.com/spot=true:NoSchedule) HOURLY pool
#     that autoscales from 0: spot-tolerant workloads still scale to zero, there is just no price discount.
#   - egress none (lean): no private network, nodes use their public IPs; otherwise the cluster joins the vRack network
# private_endpoint and admin_principals have no equivalent on OVH (no private API endpoint on the free plan, no cloud IAM
# for cluster roles): restrict the API with context.admin_cidrs (IP restrictions) and use the kubeconfig / OIDC.
terraform {
  required_version = ">= 1.8"
  required_providers {
    ovh = { source = "ovh/ovh", version = ">= 2.0, < 3.0" }
  }
}

locals {
  flavors = {
    small  = "b3-8"
    medium = "b3-16"
    large  = "b3-32"
    lean   = "b3-16"
  }
  lean     = var.node_size == "lean"
  standard = var.network.zone_count > 1
  private  = var.network.gateway
  monthly  = var.context.deletion_protection
  # lean is exactly one node: no autoscaler, no spare capacity.
  min_nodes = local.lean ? 1 : var.min_nodes
  max_nodes = local.lean ? 1 : var.max_nodes
  spot      = var.spot_enabled && !local.lean && var.spot_max_nodes > 0
}

resource "ovh_cloud_project_kube" "this" {
  service_name       = var.context.scope
  name               = var.context.name
  region             = var.network.region
  version            = var.kubernetes_version
  plan               = local.standard ? "standard" : "free"
  update_policy      = "MINIMAL_DOWNTIME"
  private_network_id = local.private ? var.network.openstack_network_id : null
  nodes_subnet_id    = local.private ? var.network.subnet_id : null

  dynamic "private_network_configuration" {
    for_each = local.private ? [1] : []
    content {
      default_vrack_gateway              = ""
      private_network_routing_as_default = true
    }
  }
}

resource "ovh_cloud_project_kube_nodepool" "general" {
  service_name   = var.context.scope
  kube_id        = ovh_cloud_project_kube.this.id
  name           = "general"
  flavor_name    = local.flavors[var.node_size]
  desired_nodes  = local.min_nodes
  min_nodes      = local.min_nodes
  max_nodes      = local.max_nodes
  autoscale      = local.max_nodes > local.min_nodes
  monthly_billed = local.monthly

  template {
    metadata {
      annotations = {}
      finalizers  = []
      labels      = { "afrotransact.com/pool" = "general" }
    }
    spec {
      unschedulable = false
      taints        = []
    }
  }
}

resource "ovh_cloud_project_kube_nodepool" "spot" {
  count          = local.spot ? 1 : 0
  service_name   = var.context.scope
  kube_id        = ovh_cloud_project_kube.this.id
  name           = "spot"
  flavor_name    = local.flavors["small"]
  desired_nodes  = 0
  min_nodes      = 0
  max_nodes      = var.spot_max_nodes
  autoscale      = true
  monthly_billed = false

  template {
    metadata {
      annotations = {}
      finalizers  = []
      labels      = { "afrotransact.com/pool" = "spot" }
    }
    spec {
      unschedulable = false
      taints = [{
        effect = "NoSchedule"
        key    = "afrotransact.com/spot"
        value  = "true"
      }]
    }
  }
}

resource "ovh_cloud_project_kube_iprestrictions" "this" {
  count        = length(var.context.admin_cidrs) > 0 ? 1 : 0
  service_name = var.context.scope
  kube_id      = ovh_cloud_project_kube.this.id
  ips          = var.context.admin_cidrs
}
