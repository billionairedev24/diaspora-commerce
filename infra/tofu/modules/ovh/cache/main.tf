# OVHcloud Public Cloud Databases for Valkey. "discovery" = one node; "production" = two nodes with failover only when
# high_availability. Flavors db1-4 / db1-7 / db1-15. TLS is always on. Same private-network / IP-restriction rules as postgres.
terraform {
  required_version = ">= 1.8"
  required_providers {
    ovh = { source = "ovh/ovh", version = ">= 2.0, < 3.0" }
  }
}

locals {
  site    = upper(regex("^[A-Za-z]+", var.network.region))
  flavor  = lookup({ small = "db1-4", medium = "db1-7", large = "db1-15" }, var.size)
  nodes   = var.high_availability ? 2 : 1
  private = var.network.gateway
  allowed = concat(var.context.admin_cidrs, [for ip in var.network.egress_ips : "${ip}/32"])
  vk      = [for e in ovh_cloud_project_database.this.endpoints : e if e.component == "valkey"]
  host    = try(local.vk[0].domain, ovh_cloud_project_database.this.endpoints[0].domain)
  port    = try(local.vk[0].port, 20185)
}

resource "ovh_cloud_project_database" "this" {
  service_name        = var.context.scope
  description         = "${var.context.name}-valkey"
  engine              = "valkey"
  version             = var.engine_version
  plan                = var.high_availability ? "production" : "discovery"
  flavor              = local.flavor
  deletion_protection = var.context.deletion_protection
  maintenance_time    = "03:30:00"

  dynamic "nodes" {
    for_each = range(local.nodes)
    content {
      region     = local.site
      network_id = local.private ? var.network.openstack_network_id : null
      subnet_id  = local.private ? var.network.subnet_id : null
    }
  }

  dynamic "ip_restrictions" {
    for_each = local.private ? [] : toset(local.allowed)
    content {
      description = "afrotransact ${var.context.environment}"
      ip          = ip_restrictions.value
    }
  }
}

resource "ovh_cloud_project_database_valkey_user" "app" {
  service_name = var.context.scope
  cluster_id   = ovh_cloud_project_database.this.id
  name         = "afrotransact"
  categories   = ["+@all"]
  channels     = ["*"]
  keys         = ["*"]
}
