# AWS secrets: one Secrets Manager secret per service at <prefix>/<env>/<service> holding a JSON document,
# KMS-encrypted. External Secrets Operator (ClusterSecretStore "platform-secrets") extracts it into a
# Kubernetes Secret - matching the key the at-service Helm library chart expects.
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws = { source = "hashicorp/aws", version = ">= 6.0, < 7.0" }
  }
}

locals {
  tags = merge(var.context.tags, { module = "secrets" })
  path = "${var.prefix}/${var.context.environment}"
}

resource "aws_kms_key" "this" {
  description             = "${var.context.name} secrets"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  tags                    = merge(local.tags, { service = "platform" })
}

resource "aws_kms_alias" "this" {
  name          = "alias/${var.context.name}-secrets"
  target_key_id = aws_kms_key.this.key_id
}

resource "aws_secretsmanager_secret" "service" {
  for_each                = toset(var.services)
  name                    = "${local.path}/${each.key}"
  description             = "Runtime configuration for ${each.key} (${var.context.environment}); synced by External Secrets Operator."
  kms_key_id              = aws_kms_key.this.arn
  recovery_window_in_days = var.context.deletion_protection ? 30 : 0
  tags                    = merge(local.tags, { service = each.key })
}

# Seed version so ESO has something to extract; humans/rotation own the value afterwards.
resource "aws_secretsmanager_secret_version" "seed" {
  for_each      = toset(var.services)
  secret_id     = aws_secretsmanager_secret.service[each.key].id
  secret_string = jsonencode(lookup(var.initial_values, each.key, {}))
  lifecycle {
    ignore_changes = [secret_string]
  }
}
