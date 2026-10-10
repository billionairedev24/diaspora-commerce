# Outputs. IDENTICAL in every env and the same whichever cloud is selected.

output "cloud" {
  value = var.cloud
}

output "cluster" {
  description = "Cluster name, endpoint and the command to fetch credentials."
  value = {
    name               = local.p.cluster.cluster_name
    endpoint           = local.p.cluster.cluster_endpoint
    oidc_issuer_url    = local.p.cluster.oidc_issuer_url
    kubeconfig_command = local.p.cluster.kubeconfig_command
  }
}

output "registry_url" {
  description = "Set as the GitHub environment variable REGISTRY_URL (deploy mirror target)."
  value       = local.p.registry.registry_url
}

output "github_oidc" {
  description = "Set as GitHub environment variables (see docs/engineering/ci-cd.md). No secrets involved."
  value = {
    deploy = local.p.gh_deploy.login
    plan   = local.p.gh_plan.login
    apply  = local.p.gh_apply.login
  }
}

output "postgres" {
  value = {
    endpoint         = local.p.postgres.endpoint
    reader_endpoint  = local.p.postgres.reader_endpoint
    port             = local.p.postgres.port
    admin_username   = local.p.postgres.admin_username
    admin_secret_ref = local.p.postgres.admin_secret_ref
    databases        = local.databases
  }
}

output "cache" {
  value = {
    host        = local.p.cache.host
    port        = local.p.cache.port
    tls_enabled = local.p.cache.tls_enabled
  }
}

output "buckets" {
  value = local.p.bucket.bucket_names
}

output "secret_names" {
  description = "Service => secret name in the cloud secret store (ExternalSecret extract key)."
  value       = local.p.secrets.secret_names
}

output "cluster_secret_store" {
  description = "spec.provider for the ESO ClusterSecretStore `platform-secrets` (consumed by infra/k8s)."
  value       = local.p.secrets.cluster_secret_store
}

output "platform_values" {
  description = "Per-service Helm values fragment (cloud + workloadIdentity) for Argo CD platform-values.yaml."
  value       = local.p.identity.helm_values
}

output "egress_ips" {
  description = "NAT egress IPs - allow-list at Stripe/carriers/Elastic/Confluent if they support it."
  value       = local.p.network.egress_ips
}

output "elasticsearch_url" {
  value = var.enable_elastic ? module.elastic[0].elasticsearch_url : null
}

output "elastic_password" {
  value     = var.enable_elastic ? module.elastic[0].elastic_password : null
  sensitive = true
}

output "kafka" {
  value = var.enable_confluent ? {
    bootstrap_servers   = module.confluent[0].bootstrap_servers
    schema_registry_url = module.confluent[0].schema_registry_url
  } : null
}
