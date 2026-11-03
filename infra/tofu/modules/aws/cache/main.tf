# AWS cache: ElastiCache for Valkey (Graviton nodes), TLS in transit, KMS at rest, AUTH token.
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws    = { source = "hashicorp/aws", version = ">= 6.0, < 7.0" }
    random = { source = "hashicorp/random", version = ">= 3.6" }
  }
}

locals {
  name = "${var.context.name}-valkey"
  tags = merge(var.context.tags, { service = "platform", module = "cache" })
  node_type = {
    small  = "cache.t4g.small"
    medium = "cache.m7g.large"
    large  = "cache.r7g.large"
  }
}

resource "random_password" "auth" {
  length  = 48
  special = false
}

resource "aws_kms_key" "this" {
  description             = "${local.name} at-rest encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  tags                    = local.tags
}

resource "aws_elasticache_subnet_group" "this" {
  name       = local.name
  subnet_ids = var.network.data_subnet_ids
  tags       = local.tags
}

resource "aws_security_group" "this" {
  name        = local.name
  description = "Valkey from inside the VPC only"
  vpc_id      = var.network.id
  tags        = local.tags
}

resource "aws_vpc_security_group_ingress_rule" "valkey" {
  security_group_id = aws_security_group.this.id
  description       = "Valkey from the VPC"
  cidr_ipv4         = var.network.cidr_block
  ip_protocol       = "tcp"
  from_port         = 6379
  to_port           = 6379
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id       = local.name
  description                = "AfroTransact ${var.context.environment} cache (carts, sessions, BFF)"
  engine                     = "valkey"
  engine_version             = "${var.engine_version}.0"
  node_type                  = local.node_type[var.size]
  num_cache_clusters         = var.high_availability ? 2 : 1
  automatic_failover_enabled = var.high_availability
  multi_az_enabled           = var.high_availability
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.this.id]
  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.this.arn
  transit_encryption_enabled = true
  auth_token                 = random_password.auth.result
  snapshot_retention_limit   = var.context.environment == "prod" ? 7 : 0
  apply_immediately          = var.context.environment != "prod"
  tags                       = local.tags
}
