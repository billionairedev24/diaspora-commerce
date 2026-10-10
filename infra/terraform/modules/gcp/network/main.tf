# GCP network: custom-mode VPC, one regional cluster subnet (with pods/services secondary ranges) and a
# data subnet for Private Service Connect, Private Google Access (Google APIs without NAT), Private
# Service Access for Cloud SQL, a PSC service-connection policy for Memorystore, optional Cloud NAT.
terraform {
  required_version = ">= 1.8"
  required_providers {
    google = { source = "hashicorp/google", version = ">= 7.0, < 9.0" }
  }
}

locals {
  project = var.context.scope
  labels = { for k, v in merge(var.context.tags, { service = "platform", module = "network" }) :
  lower(replace(k, "/[^a-zA-Z0-9_-]/", "_")) => lower(replace(v, "/[^a-zA-Z0-9_-]/", "-")) }
  apis = [
    "compute.googleapis.com", "container.googleapis.com", "servicenetworking.googleapis.com",
    "sqladmin.googleapis.com", "memorystore.googleapis.com", "networkconnectivity.googleapis.com",
    "secretmanager.googleapis.com", "artifactregistry.googleapis.com", "cloudkms.googleapis.com",
    "iam.googleapis.com", "iamcredentials.googleapis.com", "sts.googleapis.com", "storage.googleapis.com",
    "serviceusage.googleapis.com", "cloudresourcemanager.googleapis.com",
  ]
  # GKE is regional; zone_count only drives NAT and node pool zone spread.
  cluster_cidr  = cidrsubnet(var.cidr_block, 4, 0)  # nodes      /20
  data_cidr     = cidrsubnet(var.cidr_block, 8, 32) # PSC        /24
  pods_cidr     = "100.64.0.0/14"                   # pods       (non-RFC1918 space, private to VPC)
  services_cidr = cidrsubnet(var.cidr_block, 4, 1)  # services   /20
}

resource "google_project_service" "this" {
  for_each           = toset(local.apis)
  project            = local.project
  service            = each.value
  disable_on_destroy = false
}

resource "google_compute_network" "this" {
  project                         = local.project
  name                            = var.context.name
  auto_create_subnetworks         = false
  routing_mode                    = "REGIONAL"
  delete_default_routes_on_create = false
  depends_on                      = [google_project_service.this]
}

resource "google_compute_subnetwork" "cluster" {
  project                  = local.project
  name                     = "${var.context.name}-cluster"
  region                   = var.context.region
  network                  = google_compute_network.this.id
  ip_cidr_range            = local.cluster_cidr
  private_ip_google_access = true
  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = local.pods_cidr
  }
  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = local.services_cidr
  }
  log_config {
    aggregation_interval = "INTERVAL_10_MIN"
    flow_sampling        = var.context.environment == "prod" ? 0.5 : 0.1
    metadata             = "INCLUDE_ALL_METADATA"
  }
}

resource "google_compute_subnetwork" "data" {
  project                  = local.project
  name                     = "${var.context.name}-data"
  region                   = var.context.region
  network                  = google_compute_network.this.id
  ip_cidr_range            = local.data_cidr
  private_ip_google_access = true
}

# Private Service Access: Cloud SQL gets a private IP in this peered range.
resource "google_compute_global_address" "psa" {
  project       = local.project
  name          = "${var.context.name}-psa"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 20
  network       = google_compute_network.this.id
}

resource "google_service_networking_connection" "psa" {
  network                 = google_compute_network.this.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.psa.name]
  deletion_policy         = "ABANDON"
}

# Memorystore for Valkey connects via Private Service Connect into the data subnet.
resource "google_network_connectivity_service_connection_policy" "memorystore" {
  project       = local.project
  name          = "${var.context.name}-memorystore"
  location      = var.context.region
  service_class = "gcp-memorystore"
  network       = google_compute_network.this.id
  psc_config {
    subnetworks = [google_compute_subnetwork.data.id]
  }
  depends_on = [google_project_service.this]
}

resource "google_compute_router" "this" {
  count   = var.egress == "none" ? 0 : 1
  project = local.project
  name    = var.context.name
  region  = var.context.region
  network = google_compute_network.this.id
}

resource "google_compute_address" "nat" {
  count   = var.egress == "none" ? 0 : (var.egress == "single" ? 1 : var.zone_count)
  project = local.project
  name    = "${var.context.name}-nat-${count.index}"
  region  = var.context.region
  labels  = local.labels
}

# Cloud NAT is a regional managed service (already zone-resilient); per-zone just adds static IPs.
resource "google_compute_router_nat" "this" {
  count                              = var.egress == "none" ? 0 : 1
  project                            = local.project
  name                               = var.context.name
  router                             = google_compute_router.this[0].name
  region                             = var.context.region
  nat_ip_allocate_option             = "MANUAL_ONLY"
  nat_ips                            = google_compute_address.nat[*].self_link
  source_subnetwork_ip_ranges_to_nat = "LIST_OF_SUBNETWORKS"
  subnetwork {
    name                    = google_compute_subnetwork.cluster.id
    source_ip_ranges_to_nat = ["ALL_IP_RANGES"]
  }
  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

resource "google_compute_firewall" "internal" {
  project       = local.project
  name          = "${var.context.name}-allow-internal"
  network       = google_compute_network.this.id
  direction     = "INGRESS"
  source_ranges = [var.cidr_block, local.pods_cidr]
  allow {
    protocol = "tcp"
  }
  allow {
    protocol = "udp"
  }
  allow {
    protocol = "icmp"
  }
}
