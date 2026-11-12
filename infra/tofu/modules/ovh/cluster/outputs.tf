output "cluster_name" {
  value = ovh_cloud_project_kube.this.name
}

output "cluster_endpoint" {
  value = nonsensitive(ovh_cloud_project_kube.this.kubeconfig_attributes[0].host)
}

output "cluster_ca_certificate" {
  description = "Base64 PEM of the cluster CA."
  value       = ovh_cloud_project_kube.this.kubeconfig_attributes[0].cluster_ca_certificate
  sensitive   = true
}

output "oidc_issuer_url" {
  description = "OVH MKS has no workload identity issuer; pods get cloud access through scoped S3 users and the secret store."
  value       = ""
}

output "workload_identity" {
  description = "Handle for the identity module."
  value = {
    cloud        = "ovh"
    cluster_name = ovh_cloud_project_kube.this.name
    cluster_id   = ovh_cloud_project_kube.this.id
  }
}

output "node_identity" {
  description = "Not used on OVH: images are pulled with an image pull secret (GHCR read-only token, or a Harbor robot account)."
  value       = ""
}

output "kubeconfig_command" {
  value = "OVH Control Panel > Public Cloud > Managed Kubernetes > ${ovh_cloud_project_kube.this.name} > Download the kubeconfig (or the API: POST /cloud/project/<project>/kube/${ovh_cloud_project_kube.this.id}/kubeconfig on the same OVH endpoint)"
}
