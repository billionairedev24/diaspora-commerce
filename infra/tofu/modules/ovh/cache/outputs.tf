output "host" {
  value = local.host
}

output "port" {
  value = local.port
}

output "tls_enabled" {
  value = true
}

output "auth_token" {
  value     = ovh_cloud_project_database_valkey_user.app.password
  sensitive = true
}

output "cache_id" {
  value = ovh_cloud_project_database.this.id
}
