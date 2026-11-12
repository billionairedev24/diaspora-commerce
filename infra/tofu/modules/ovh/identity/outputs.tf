output "identities" {
  description = "Always empty: no per-service cloud identities on OVH."
  value       = {}
}

output "helm_values" {
  description = "Service => values fragment for platform-values.yaml (cloud + workloadIdentity)."
  value = { for k, v in var.services : k => {
    cloud            = "ovh"
    workloadIdentity = {}
  } }
}
