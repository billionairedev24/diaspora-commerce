output "endpoint" {
  value = azurerm_postgresql_flexible_server.this.fqdn
}

output "reader_endpoint" {
  description = "No read replica by default - same as the primary (add replicas only where measured)."
  value       = azurerm_postgresql_flexible_server.this.fqdn
}

output "port" {
  value = 5432
}

output "admin_username" {
  value = azurerm_postgresql_flexible_server.this.administrator_login
}

output "admin_secret_ref" {
  description = "Key Vault secret id holding {username,password,host,port}."
  value       = azurerm_key_vault_secret.admin.versionless_id
}

output "databases" {
  value = [for d in azurerm_postgresql_flexible_server_database.this : d.name]
}

output "server_id" {
  value = azurerm_postgresql_flexible_server.this.id
}
