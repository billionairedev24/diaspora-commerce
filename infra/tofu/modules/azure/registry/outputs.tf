output "registry_url" {
  description = "Prefix; image = <registry_url>/<service>:<tag>."
  value       = "${azurerm_container_registry.this.login_server}/${local.namespace}"
}

output "repository_urls" {
  value = { for r in var.repositories : r => "${azurerm_container_registry.this.login_server}/${local.namespace}/${r}" }
}

output "registry" {
  description = "Handle for github-oidc (deploy access)."
  value = {
    cloud = "azure"
    host  = azurerm_container_registry.this.login_server
    url   = "${azurerm_container_registry.this.login_server}/${local.namespace}"
    id    = azurerm_container_registry.this.id
    name  = azurerm_container_registry.this.name
  }
}
