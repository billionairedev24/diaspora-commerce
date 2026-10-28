output "endpoint" {
  description = "Writer host name."
  value       = aws_rds_cluster.this.endpoint
}

output "reader_endpoint" {
  value = aws_rds_cluster.this.reader_endpoint
}

output "port" {
  value = aws_rds_cluster.this.port
}

output "admin_username" {
  value = aws_rds_cluster.this.master_username
}

output "admin_secret_ref" {
  description = "Secrets Manager ARN of the RDS-managed admin credential (rotated by RDS)."
  value       = aws_rds_cluster.this.master_user_secret[0].secret_arn
}

output "databases" {
  description = "Databases the bootstrap job must create (one per service)."
  value       = var.databases
}

output "server_id" {
  value = aws_rds_cluster.this.arn
}
