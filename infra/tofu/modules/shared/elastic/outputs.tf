output "elasticsearch_url" {
  value = ec_deployment.this.elasticsearch.https_endpoint
}

output "cloud_id" {
  value = ec_deployment.this.elasticsearch.cloud_id
}

output "kibana_url" {
  value = ec_deployment.this.kibana.https_endpoint
}

output "elastic_username" {
  value = ec_deployment.this.elasticsearch_username
}

output "elastic_password" {
  description = "Bootstrap superuser - use only to create scoped API keys for search/indexer (scripts/elastic-ilm.sh)."
  value       = ec_deployment.this.elasticsearch_password
  sensitive   = true
}

output "deployment_id" {
  value = ec_deployment.this.id
}
