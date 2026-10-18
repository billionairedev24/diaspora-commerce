output "network_id" {
  value = google_compute_network.this.id
}

output "cluster_subnet_ids" {
  value = [google_compute_subnetwork.cluster.id]
}

output "data_subnet_ids" {
  value = [google_compute_subnetwork.data.id]
}

output "network" {
  description = "Opaque handle consumed by the cluster, postgres, cache and bucket modules of the same cloud."
  value = {
    cloud                  = "gcp"
    project                = local.project
    id                     = google_compute_network.this.id
    self_link              = google_compute_network.this.self_link
    cidr_block             = var.cidr_block
    zone_count             = var.zone_count
    egress                 = var.egress
    cluster_subnet_id      = google_compute_subnetwork.cluster.id
    pods_range_name        = "pods"
    services_range_name    = "services"
    data_subnet_id         = google_compute_subnetwork.data.id
    psa_connection         = google_service_networking_connection.psa.id
    memorystore_psc_policy = google_network_connectivity_service_connection_policy.memorystore.id
    egress_ips             = google_compute_address.nat[*].address
  }
}
