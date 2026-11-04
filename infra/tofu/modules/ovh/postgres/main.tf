# OVHcloud Public Cloud Databases for PostgreSQL. Cheapest shape that fits:
#   plan "discovery" = ONE node (dev, uat, lean); "production" = 2 nodes (primary + standby) only when high_availability.
#   flavor db1-4 / db1-7 / db1-15 for small / medium / large (the smallest class that carries the 24 service databases).
# storage_gb and backup_retention_days are fixed by the plan (included storage, 7 days of backups, PITR on production);
# the inputs are accepted for interface parity. The admin credential is written to the Secret Manager when a store is given.
# Private network: used when the network has a gateway; otherwise the cluster nodes are on public IPs and the server
# only accepts admin_cidrs and the gateway IPs (ip_restrictions) - add the node IPs there for the lean profile.
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
  pg      = [for e in ovh_cloud_project_database.this.endpoints : e if e.component == "postgresql"]
  host    = try(local.pg[0].domain, ovh_cloud_project_database.this.endpoints[0].domain)
  port    = try(local.pg[0].port, 20184)
}

resource "ovh_cloud_project_database" "this" {
  service_name        = var.context.scope
  description         = "${var.context.name}-postgres"
  engine              = "postgresql"
  version             = var.engine_version
  plan                = var.high_availability ? "production" : "discovery"
  flavor              = local.flavor
  deletion_protection = var.context.deletion_protection
  backup_time         = "02:00:00"
  maintenance_time    = "03:00:00"

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

resource "ovh_cloud_project_database_database" "this" {
  for_each     = toset(var.databases)
  service_name = var.context.scope
  engine       = "postgresql"
  cluster_id   = ovh_cloud_project_database.this.id
  name         = each.value
}

# "avnadmin" maps to the cluster's admin user: this resets and reads its password.
resource "ovh_cloud_project_database_postgresql_user" "admin" {
  service_name = var.context.scope
  cluster_id   = ovh_cloud_project_database.this.id
  name         = "avnadmin"
}

resource "ovh_okms_secret" "admin" {
  count   = var.secret_store == null ? 0 : 1
  okms_id = var.secret_store.okms_id
  path    = "${var.secret_store.path}/${var.context.environment}/postgres-admin"
  version = {
    data = jsonencode({
      username = ovh_cloud_project_database_postgresql_user.admin.name
      password = ovh_cloud_project_database_postgresql_user.admin.password
      host     = local.host
      port     = local.port
    })
  }
}
