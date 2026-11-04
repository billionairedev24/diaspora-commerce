output "identities" {
  description = "Service => value for the service account annotation (managed identity client id)."
  value       = { for k, i in azurerm_user_assigned_identity.this : k => i.client_id }
}

output "helm_values" {
  description = "Service => values fragment for platform-values.yaml (cloud + workloadIdentity)."
  value = { for k, i in azurerm_user_assigned_identity.this : k => {
    cloud            = "azure"
    workloadIdentity = { azure = { clientId = i.client_id } }
  } }
}
