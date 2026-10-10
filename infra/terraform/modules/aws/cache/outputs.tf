output "host" {
  value = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "port" {
  value = aws_elasticache_replication_group.this.port
}

output "tls_enabled" {
  value = true
}

output "auth_token" {
  description = "Password for AUTH (empty when the cloud uses IAM auth instead)."
  value       = random_password.auth.result
  sensitive   = true
}

output "cache_id" {
  value = aws_elasticache_replication_group.this.arn
}
