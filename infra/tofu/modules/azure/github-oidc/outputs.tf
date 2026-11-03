output "login" {
  description = "Inputs for azure/login (store as GitHub environment variables)."
  value = {
    cloud           = "azure"
    client_id       = azurerm_user_assigned_identity.this.client_id
    tenant_id       = data.azurerm_client_config.this.tenant_id
    subscription_id = data.azurerm_client_config.this.subscription_id
  }
}

output "principal" {
  value = azurerm_user_assigned_identity.this.principal_id
}

output "provider_ref" {
  description = "Unused on Azure (no shared provider object); kept for interface parity."
  value       = ""
}
