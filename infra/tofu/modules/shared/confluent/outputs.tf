output "bootstrap_servers" {
  value = replace(confluent_kafka_cluster.this.bootstrap_endpoint, "SASL_SSL://", "")
}

output "schema_registry_url" {
  value = data.confluent_schema_registry_cluster.this.rest_endpoint
}

output "environment_id" {
  value = confluent_environment.this.id
}

output "cluster_id" {
  value = confluent_kafka_cluster.this.id
}

output "service_credentials" {
  description = "Service => {key, secret} for SASL/PLAIN. Seeded into each service's secret (KAFKA_API_KEY / KAFKA_API_SECRET)."
  value       = { for k, v in confluent_api_key.service : k => { key = v.id, secret = v.secret } }
  sensitive   = true
}
