# AWS postgres: ONE Aurora PostgreSQL 17 cluster per environment (Serverless v2, Graviton-backed).
# Per-service databases + owner roles are created by the bootstrap job (scripts/postgres-bootstrap.sh,
# run as an Argo CD PreSync Job with the admin secret) because the RDS API cannot create databases.
#   small  -> Serverless v2 0 - 4 ACU, auto-pauses when idle (dev scale-to-zero)
#   medium -> Serverless v2 0.5 - 16 ACU
#   large  -> Serverless v2 2 - 64 ACU (prod; consider provisioned db.r8g + Reserved Instances once load is flat)
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws = { source = "hashicorp/aws", version = ">= 6.0, < 7.0" }
  }
}

locals {
  name = "${var.context.name}-pg"
  tags = merge(var.context.tags, { service = "platform", module = "postgres" })
  capacity = {
    small  = { min = 0, max = 4 }
    medium = { min = 0.5, max = 16 }
    large  = { min = 2, max = 64 }
  }
  family = "aurora-postgresql${var.engine_version}"
}

data "aws_rds_engine_version" "this" {
  engine                 = "aurora-postgresql"
  parameter_group_family = local.family
  latest                 = true
}

resource "aws_kms_key" "this" {
  description             = "${local.name} storage encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  tags                    = local.tags
}

resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = var.network.data_subnet_ids
  tags       = local.tags
}

resource "aws_security_group" "this" {
  name        = local.name
  description = "PostgreSQL from inside the VPC only"
  vpc_id      = var.network.id
  tags        = local.tags
}

resource "aws_vpc_security_group_ingress_rule" "postgres" {
  security_group_id = aws_security_group.this.id
  description       = "PostgreSQL from the VPC"
  cidr_ipv4         = var.network.cidr_block
  ip_protocol       = "tcp"
  from_port         = 5432
  to_port           = 5432
}

resource "aws_rds_cluster_parameter_group" "this" {
  name   = local.name
  family = local.family
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }
  tags = local.tags
}

resource "aws_rds_cluster" "this" {
  cluster_identifier                  = local.name
  engine                              = "aurora-postgresql"
  engine_mode                         = "provisioned"
  engine_version                      = data.aws_rds_engine_version.this.version
  database_name                       = "platform"
  master_username                     = "at_admin"
  manage_master_user_password         = true
  iam_database_authentication_enabled = true
  db_subnet_group_name                = aws_db_subnet_group.this.name
  vpc_security_group_ids              = [aws_security_group.this.id]
  db_cluster_parameter_group_name     = aws_rds_cluster_parameter_group.this.name
  storage_encrypted                   = true
  kms_key_id                          = aws_kms_key.this.arn
  backup_retention_period             = var.backup_retention_days
  preferred_backup_window             = "07:00-08:00"
  copy_tags_to_snapshot               = true
  deletion_protection                 = var.context.deletion_protection
  skip_final_snapshot                 = !var.context.deletion_protection
  final_snapshot_identifier           = var.context.deletion_protection ? "${local.name}-final" : null
  enabled_cloudwatch_logs_exports     = ["postgresql"]

  serverlessv2_scaling_configuration {
    min_capacity             = local.capacity[var.size].min
    max_capacity             = local.capacity[var.size].max
    seconds_until_auto_pause = local.capacity[var.size].min == 0 ? 1800 : null
  }

  tags = local.tags
}

resource "aws_rds_cluster_instance" "this" {
  count                        = var.high_availability ? 2 : 1
  identifier                   = "${local.name}-${count.index}"
  cluster_identifier           = aws_rds_cluster.this.id
  engine                       = aws_rds_cluster.this.engine
  engine_version               = aws_rds_cluster.this.engine_version
  instance_class               = "db.serverless"
  db_subnet_group_name         = aws_db_subnet_group.this.name
  performance_insights_enabled = true
  auto_minor_version_upgrade   = true
  tags                         = local.tags
}
