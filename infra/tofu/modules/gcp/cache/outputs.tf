output "host" {
  value = try(google_memorystore_instance.this.endpoints[0].connections[0].psc_auto_connection[0].ip_address, "")
}

output "port" {
  value = 6379
}

output "tls_enabled" {
  value = true
}

output "auth_token" {
  description = "Empty: Memorystore for Valkey uses network isolation (or IAM auth) instead of a password."
  value       = ""
  sensitive   = true
}

output "cache_id" {
  value = google_memorystore_instance.this.id
}
