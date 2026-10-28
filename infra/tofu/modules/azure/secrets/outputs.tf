output "store" {
  description = "Handle for the identity and postgres modules."
  value = {
    cloud        = "azure"
    prefix       = local.prefix
    path         = local.prefix
    separator    = "-"
    key_vault_id = azurerm_key_vault.this.id
    vault_url    = azurerm_key_vault.this.vault_uri
  }
}

output "secret_names" {
  description = "Service => secret name (the ExternalSecret `extract.key`)."
  value       = { for k, s in azurerm_key_vault_secret.service : k => s.name }
}

output "cluster_secret_store" {
  description = "spec.provider for the External Secrets ClusterSecretStore named platform-secrets."
  value = {
    azurekv = {
      vaultUrl = azurerm_key_vault.this.vault_uri
      authType = "WorkloadIdentity"
      serviceAccountRef = {
        name      = "external-secrets"
        namespace = "external-secrets"
      }
    }
  }
}
