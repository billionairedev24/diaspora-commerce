output "store" {
  description = "Handle for the identity and postgres modules."
  value = {
    cloud      = "aws"
    region     = var.context.region
    prefix     = local.path
    path       = local.path
    separator  = "/"
    kms_key_id = aws_kms_key.this.arn
    arn_prefix = "arn:aws:secretsmanager:${var.context.region}:*:secret:${local.path}/*"
  }
}

output "secret_names" {
  description = "Service => secret name (the ExternalSecret `extract.key`)."
  value       = { for k, s in aws_secretsmanager_secret.service : k => s.name }
}

output "cluster_secret_store" {
  description = "spec.provider for the External Secrets ClusterSecretStore named platform-secrets."
  value = {
    aws = {
      service = "SecretsManager"
      region  = var.context.region
      auth = {
        jwt = { serviceAccountRef = { name = "external-secrets", namespace = "external-secrets" } }
      }
    }
  }
}
