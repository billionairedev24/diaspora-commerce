output "identities" {
  description = "Service => Object Storage access key id (the secret key lives in the Secret Manager)."
  value       = { for k, c in ovh_cloud_project_user_s3_credential.this : k => c.access_key_id }
}

output "helm_values" {
  description = "Service => values fragment for platform-values.yaml (cloud + workloadIdentity)."
  value = { for k, v in var.services : k => {
    cloud            = "ovh"
    workloadIdentity = {}
    storageSecret    = contains(keys(local.with_buckets), k) ? "${coalesce(try(var.secret_store.path, null), "afrotransact")}/${var.context.environment}/${k}-storage" : null
  } }
}
