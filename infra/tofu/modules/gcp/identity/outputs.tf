output "identities" {
  description = "Service => value for the service account annotation (GSA email)."
  value       = { for k, s in google_service_account.this : k => s.email }
}

output "helm_values" {
  description = "Service => values fragment for platform-values.yaml (cloud + workloadIdentity)."
  value = { for k, s in google_service_account.this : k => {
    cloud            = "gcp"
    workloadIdentity = { gcp = { serviceAccount = s.email } }
  } }
}
