# GCP cluster: regional GKE Standard (so node pools mirror EKS/AKS exactly) with
#   - "system" pool: small amd64 e2 nodes for third-party images that ship amd64 only
#   - "general" pool: Axion C4A arm64 on-demand (GKE taints arm nodes kubernetes.io/arch=arm64:NoSchedule;
#     set nodeSelector kubernetes.io/arch: arm64 in platform-values.yaml and GKE adds the toleration)
#   - "spot" pool: Axion arm64 Spot VMs tainted afrotransact.com/spot=true:NoSchedule, scales to zero
#   - Workload Identity, Dataplane V2, private nodes, Shielded nodes, KMS application-layer secret encryption,
#     cost allocation, optimize-utilization autoscaler profile.
# GKE Autopilot is a valid lower-ops alternative (spot pods + arm64 via node selectors); see modules/README.md.
terraform {
  required_version = ">= 1.8"
  required_providers {
    google = { source = "hashicorp/google", version = ">= 7.0, < 9.0" }
  }
}

data "google_project" "this" {
  project_id = local.project
}

locals {
  project = var.context.scope
  name    = var.context.name
  labels = { for k, v in merge(var.context.tags, { service = "platform", module = "cluster" }) :
  lower(replace(k, "/[^a-zA-Z0-9_-]/", "_")) => lower(replace(v, "/[^a-zA-Z0-9_-]/", "-")) }
  machine = {
    small  = "c4a-standard-2"
    medium = "c4a-standard-4"
    large  = "c4a-standard-8"
  }
  zones = slice(data.google_compute_zones.this.names, 0, min(var.network.zone_count, length(data.google_compute_zones.this.names)))
}

data "google_compute_zones" "this" {
  project = local.project
  region  = var.context.region
}

# ---------------------------------------------------------------- encryption
resource "google_kms_key_ring" "this" {
  project  = local.project
  name     = "${local.name}-gke"
  location = var.context.region
}

resource "google_kms_crypto_key" "secrets" {
  name            = "secrets"
  key_ring        = google_kms_key_ring.this.id
  rotation_period = "7776000s"
  labels          = local.labels
}

resource "google_kms_crypto_key_iam_member" "gke" {
  crypto_key_id = google_kms_crypto_key.secrets.id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:service-${data.google_project.this.number}@container-engine-robot.iam.gserviceaccount.com"
}

# ---------------------------------------------------------------- node identity
resource "google_service_account" "nodes" {
  project      = local.project
  account_id   = substr("${local.name}-nodes", 0, 30)
  display_name = "GKE nodes (${local.name})"
}

resource "google_project_iam_member" "nodes" {
  for_each = toset([
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
    "roles/monitoring.viewer",
    "roles/stackdriver.resourceMetadata.writer",
    "roles/autoscaling.metricsWriter",
  ])
  project = local.project
  role    = each.value
  member  = "serviceAccount:${google_service_account.nodes.email}"
}

# ---------------------------------------------------------------- control plane
resource "google_container_cluster" "this" {
  provider            = google
  project             = local.project
  name                = local.name
  location            = var.context.region
  node_locations      = local.zones
  network             = var.network.id
  subnetwork          = var.network.cluster_subnet_id
  min_master_version  = var.kubernetes_version
  deletion_protection = var.context.deletion_protection
  resource_labels     = local.labels

  remove_default_node_pool = true
  initial_node_count       = 1

  datapath_provider = "ADVANCED_DATAPATH"
  networking_mode   = "VPC_NATIVE"
  ip_allocation_policy {
    cluster_secondary_range_name  = var.network.pods_range_name
    services_secondary_range_name = var.network.services_range_name
  }

  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = var.private_endpoint
  }

  dynamic "master_authorized_networks_config" {
    for_each = length(var.context.admin_cidrs) > 0 ? [1] : []
    content {
      dynamic "cidr_blocks" {
        for_each = var.context.admin_cidrs
        content {
          cidr_block   = cidr_blocks.value
          display_name = "admin-${cidr_blocks.key}"
        }
      }
    }
  }

  release_channel {
    channel = var.context.environment == "prod" ? "STABLE" : "REGULAR"
  }

  workload_identity_config {
    workload_pool = "${local.project}.svc.id.goog"
  }

  database_encryption {
    state    = "ENCRYPTED"
    key_name = google_kms_crypto_key.secrets.id
  }

  enable_shielded_nodes = true

  cost_management_config {
    enabled = true
  }

  cluster_autoscaling {
    autoscaling_profile = "OPTIMIZE_UTILIZATION"
  }

  addons_config {
    gce_persistent_disk_csi_driver_config {
      enabled = true
    }
    http_load_balancing {
      disabled = false
    }
  }

  maintenance_policy {
    recurring_window {
      start_time = "2026-01-01T07:00:00Z"
      end_time   = "2026-01-01T11:00:00Z"
      recurrence = "FREQ=WEEKLY;BYDAY=SA,SU"
    }
  }

  depends_on = [google_kms_crypto_key_iam_member.gke]
}

# Cluster admins: IAM members such as "group:platform@afrotransact.com".
resource "google_project_iam_member" "admins" {
  for_each = toset(var.admin_principals)
  project  = local.project
  role     = "roles/container.admin"
  member   = each.value
}

# ---------------------------------------------------------------- node pools
locals {
  pools = merge(
    {
      system = {
        machine = "e2-standard-2", spot = false, min = 1, max = 2, arm = false
        labels  = { "afrotransact.com/pool" = "system" }, taints = []
      }
      general = {
        machine = local.machine[var.node_size], spot = false, min = var.min_nodes, max = var.max_nodes, arm = true
        labels  = { "afrotransact.com/pool" = "general" }, taints = []
      }
    },
    var.spot_enabled ? {
      spot = {
        machine = local.machine[var.node_size], spot = true, min = 0, max = var.spot_max_nodes, arm = true
        labels  = { "afrotransact.com/pool" = "spot" }
        taints  = [{ key = "afrotransact.com/spot", value = "true", effect = "NO_SCHEDULE" }]
      }
    } : {}
  )
}

resource "google_container_node_pool" "this" {
  for_each = local.pools
  project  = local.project
  name     = each.key
  cluster  = google_container_cluster.this.id
  location = var.context.region

  autoscaling {
    location_policy      = each.value.spot ? "ANY" : "BALANCED"
    total_min_node_count = each.value.min
    total_max_node_count = each.value.max
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }

  upgrade_settings {
    strategy        = "SURGE"
    max_surge       = 1
    max_unavailable = 0
  }

  node_config {
    machine_type    = each.value.machine
    spot            = each.value.spot
    disk_type       = each.value.arm ? "hyperdisk-balanced" : "pd-balanced"
    disk_size_gb    = 50
    image_type      = "COS_CONTAINERD"
    service_account = google_service_account.nodes.email
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    labels          = each.value.labels
    resource_labels = local.labels

    dynamic "taint" {
      for_each = each.value.taints
      content {
        key    = taint.value.key
        value  = taint.value.value
        effect = taint.value.effect
      }
    }

    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }
  }

  lifecycle {
    ignore_changes = [initial_node_count]
  }
}
