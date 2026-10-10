output "endpoint" {
  description = "Private IP of the primary."
  value       = google_sql_database_instance.this.private_ip_address
}

output "reader_endpoint" {
  description = "No read replica by default - same as the primary (add replicas only where measured)."
  value       = google_sql_database_instance.this.private_ip_address
}

output "port" {
  value = 5432
}

output "admin_username" {
  value = google_sql_user.admin.name
}

output "admin_secret_ref" {
  description = "Secret Manager secret holding {username,password,host,port}."
  value       = google_secret_manager_secret.admin.id
}

output "databases" {
  value = [for d in google_sql_database.this : d.name]
}

output "server_id" {
  value = google_sql_database_instance.this.connection_name
}
