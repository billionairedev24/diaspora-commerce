# OVHcloud network: ONE vRack private network + subnet per environment (private networks are free), and a managed
# gateway only when the workloads need internet egress (Stripe, OpenRouter, carriers ...).
#   egress = none      -> no gateway: nodes keep their public IPs (OVH does not bill public IPv4 on instances or egress
#                         traffic), the lean single-node profile of docs/engineering/cloud-cost.md. Cheapest.
#   egress = single    -> one gateway (smallest model "s"); every node egresses through its fixed public IP
#   egress = per-zone  -> same single gateway: an OVH gateway is regional, not per zone (the per-zone NAT of the other
#                         clouds does not exist here), so there is no extra cost for asking for it.
# zone_count is accepted for interface parity; OVH regions are single-AZ except the 3-AZ regions, where the cluster
# module spreads node pools over zones itself.
terraform {
  required_version = ">= 1.8"
  required_providers {
    ovh = { source = "ovh/ovh", version = ">= 2.0, < 3.0" }
  }
}

locals {
  # DHCP pool inside a /20 carved from cidr_block. Environments use different VLAN ids so they can share a project.
  lan     = cidrsubnet(var.cidr_block, 4, 0)
  vlan_id = lookup({ dev = 10, uat = 20, prod = 30 }, var.context.environment, 0)
  gateway = var.egress != "none"
}

resource "ovh_cloud_project_network_private" "this" {
  service_name = var.context.scope
  name         = var.context.name
  vlan_id      = local.vlan_id
  regions      = [var.context.region]
}

resource "ovh_cloud_project_network_private_subnet" "this" {
  service_name = var.context.scope
  network_id   = ovh_cloud_project_network_private.this.id
  region       = var.context.region
  network      = local.lan
  start        = cidrhost(local.lan, 10)
  end          = cidrhost(local.lan, 4000)
  dhcp         = true
  no_gateway   = !local.gateway
}

resource "ovh_cloud_project_gateway" "this" {
  count        = local.gateway ? 1 : 0
  service_name = var.context.scope
  name         = var.context.name
  model        = "s"
  region       = var.context.region
  network_id   = tolist(ovh_cloud_project_network_private.this.regions_attributes[*].openstackid)[0]
  subnet_id    = ovh_cloud_project_network_private_subnet.this.id
}
