# AWS identity: one IRSA role per service, trusted only by system:serviceaccount:<ns>:<sa> of this cluster.
# Grants: read/write on the listed buckets (+ KMS via S3), and - for External Secrets Operator only -
# read on the environment's secret prefix.
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws = { source = "hashicorp/aws", version = ">= 6.0, < 7.0" }
  }
}

locals {
  tags = merge(var.context.tags, { module = "identity" })
  sa   = { for k, v in var.services : k => coalesce(v.service_account, k) }
}

data "aws_iam_policy_document" "trust" {
  for_each = var.services
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [var.cluster.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.cluster.oidc_issuer_host}:sub"
      values   = ["system:serviceaccount:${each.value.namespace}:${local.sa[each.key]}"]
    }
    condition {
      test     = "StringEquals"
      variable = "${var.cluster.oidc_issuer_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "this" {
  for_each             = var.services
  name                 = substr("${var.context.name}-${each.key}", 0, 64)
  assume_role_policy   = data.aws_iam_policy_document.trust[each.key].json
  max_session_duration = 3600
  tags                 = merge(local.tags, { service = each.key })
}

data "aws_iam_policy_document" "access" {
  for_each = { for k, v in var.services : k => v if length(v.buckets) > 0 || v.secrets }

  dynamic "statement" {
    for_each = length(each.value.buckets) > 0 ? [1] : []
    content {
      sid       = "Buckets"
      actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket", "s3:GetObjectVersion"]
      resources = flatten([for b in each.value.buckets : [var.buckets[b].id, "${var.buckets[b].id}/*"]])
    }
  }
  dynamic "statement" {
    for_each = length(each.value.buckets) > 0 ? [1] : []
    content {
      sid       = "BucketKms"
      actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
      resources = ["*"]
      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["s3.${var.context.region}.amazonaws.com"]
      }
    }
  }
  dynamic "statement" {
    for_each = each.value.secrets ? [1] : []
    content {
      sid       = "Secrets"
      actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret", "secretsmanager:ListSecretVersionIds"]
      resources = [var.secret_store.arn_prefix]
    }
  }
  dynamic "statement" {
    for_each = each.value.secrets ? [1] : []
    content {
      sid       = "SecretsKms"
      actions   = ["kms:Decrypt"]
      resources = [var.secret_store.kms_key_id]
    }
  }
}

resource "aws_iam_role_policy" "access" {
  for_each = data.aws_iam_policy_document.access
  name     = "access"
  role     = aws_iam_role.this[each.key].id
  policy   = each.value.json
}
