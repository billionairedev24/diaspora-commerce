# GCP cache: Memorystore for Valkey (cluster mode disabled), reached over Private Service Connect via the
# network module's service-connection policy, TLS in transit (server authentication).
# Auth: Memorystore for Valkey supports IAM auth or none; we use AUTH_DISABLED + private PSC endpoint +
# TLS so the application config is the same password-less URL; switch authorization_mode to IAM_AUTH
# when the gateway-bff/cart clients support token refresh.
terraform {
  required_version = ">= 1.8"
  required_providers {
    google = { source = "hashicorp/google", version = ">= 7.0, < 9.0" }
  }
}

locals {
  project = var.context.scope
  labels = { for k, v in merge(var.context.tags, { service = "platform", module = "cache" }) :
  lower(replace(k, "/[^a-zA-Z0-9_-]/", "_")) => lower(replace(v, "/[^a-zA-Z0-9_-]/", "-")) }
  node_type = {
    small  = "SHARED_CORE_NANO"
    medium = "STANDARD_SMALL"
    large  = "HIGHMEM_MEDIUM"
  }
}

resource "google_memorystore_instance" "this" {
  project                     = local.project
  instance_id                 = "${var.context.name}-valkey"
  location                    = var.context.region
  engine_version              = "VALKEY_${var.engine_version}_0"
  mode                        = "CLUSTER_DISABLED"
  shard_count                 = 1
  replica_count               = var.high_availability ? 1 : 0
  node_type                   = local.node_type[var.size]
  authorization_mode          = "AUTH_DISABLED"
  transit_encryption_mode     = "SERVER_AUTHENTICATION"
  deletion_protection_enabled = var.context.deletion_protection
  labels                      = local.labels

  zone_distribution_config {
    mode = var.high_availability ? "MULTI_ZONE" : "SINGLE_ZONE"
  }

  desired_auto_created_endpoints {
    network    = var.network.id
    project_id = local.project
  }

  persistence_config {
    mode = var.context.environment == "prod" ? "RDB" : "DISABLED"
  }

  depends_on = [var.network]
}
