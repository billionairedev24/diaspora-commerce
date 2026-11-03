output "cluster_name" {
  value = google_container_cluster.this.name
}

output "cluster_endpoint" {
  value = "https://${google_container_cluster.this.endpoint}"
}

output "cluster_ca_certificate" {
  description = "Base64 PEM of the cluster CA."
  value       = google_container_cluster.this.master_auth[0].cluster_ca_certificate
}

output "oidc_issuer_url" {
  value = "https://container.googleapis.com/v1/projects/${local.project}/locations/${var.context.region}/clusters/${google_container_cluster.this.name}"
}

output "workload_identity" {
  description = "Handle for the identity module."
  value = {
    cloud         = "gcp"
    project       = local.project
    cluster_name  = google_container_cluster.this.name
    workload_pool = "${local.project}.svc.id.goog"
  }
}

output "node_identity" {
  description = "Principal the nodes pull images as."
  value       = "serviceAccount:${google_service_account.nodes.email}"
}

output "kubeconfig_command" {
  value = "gcloud container clusters get-credentials ${google_container_cluster.this.name} --region ${var.context.region} --project ${local.project}"
}
