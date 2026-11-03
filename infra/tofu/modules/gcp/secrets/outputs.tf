output "store" {
  description = "Handle for the identity and postgres modules."
  value = {
    cloud     = "gcp"
    project   = local.project
    prefix    = local.prefix
    path      = local.prefix
    separator = "-"
  }
}

output "secret_names" {
  description = "Service => secret id (the ExternalSecret `extract.key`)."
  value       = { for k, s in google_secret_manager_secret.service : k => s.secret_id }
}

output "cluster_secret_store" {
  description = "spec.provider for the External Secrets ClusterSecretStore named platform-secrets."
  value = {
    gcpsm = {
      projectID = local.project
      auth = {
        workloadIdentity = {
          clusterLocation   = var.context.region
          clusterName       = var.context.name
          serviceAccountRef = { name = "external-secrets", namespace = "external-secrets" }
        }
      }
    }
  }
}
