output "endpoint" {
  value = local.host
}

output "reader_endpoint" {
  description = "No read replica by default - same as the primary (add replicas only where measured)."
  value       = local.host
}

output "port" {
  value = local.port
}

output "admin_username" {
  value = ovh_cloud_project_database_postgresql_user.admin.name
}

output "admin_secret_ref" {
  description = "Secret Manager path holding {username,password,host,port}; empty without a secret store."
  value       = try(ovh_okms_secret.admin[0].path, "")
}

output "databases" {
  value = [for d in ovh_cloud_project_database_database.this : d.name]
}

output "server_id" {
  value = ovh_cloud_project_database.this.id
}
