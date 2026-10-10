output "host" {
  value = azurerm_redis_cache.this.hostname
}

output "port" {
  value = azurerm_redis_cache.this.ssl_port
}

output "tls_enabled" {
  value = true
}

output "auth_token" {
  value     = azurerm_redis_cache.this.primary_access_key
  sensitive = true
}

output "cache_id" {
  value = azurerm_redis_cache.this.id
}
