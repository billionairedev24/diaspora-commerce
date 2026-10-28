# AWS buckets: private S3 buckets with SSE-KMS (bucket keys), public access blocked, TLS-only policy,
# BucketOwnerEnforced, optional versioning, Object Lock (COMPLIANCE when deletion_protection, else
# GOVERNANCE) for KYC evidence, and lifecycle tiering to Standard-IA / Glacier Instant Retrieval.
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws    = { source = "hashicorp/aws", version = ">= 6.0, < 7.0" }
    random = { source = "hashicorp/random", version = ">= 3.6" }
  }
}

locals {
  tags = merge(var.context.tags, { module = "bucket" })
}

resource "random_id" "suffix" {
  byte_length = 3
}

resource "aws_kms_key" "this" {
  description             = "${var.context.name} object storage encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  tags                    = merge(local.tags, { service = "platform" })
}

resource "aws_s3_bucket" "this" {
  for_each            = var.buckets
  bucket              = "${var.context.name}-${each.key}-${random_id.suffix.hex}"
  object_lock_enabled = each.value.object_lock
  force_destroy       = !var.context.deletion_protection && !each.value.object_lock
  tags                = merge(local.tags, { service = "platform", bucket = each.key })
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each                = var.buckets
  bucket                  = aws_s3_bucket.this[each.key].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = var.buckets
  bucket   = aws_s3_bucket.this[each.key].id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = var.buckets
  bucket   = aws_s3_bucket.this[each.key].id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.this.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_versioning" "this" {
  for_each = var.buckets
  bucket   = aws_s3_bucket.this[each.key].id
  versioning_configuration {
    # Object Lock requires versioning.
    status = each.value.versioning || each.value.object_lock ? "Enabled" : "Suspended"
  }
}

resource "aws_s3_bucket_object_lock_configuration" "this" {
  for_each   = { for k, v in var.buckets : k => v if v.object_lock }
  bucket     = aws_s3_bucket.this[each.key].id
  depends_on = [aws_s3_bucket_versioning.this]
  rule {
    default_retention {
      mode = var.context.deletion_protection ? "COMPLIANCE" : "GOVERNANCE"
      days = max(each.value.retention_days, 1)
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  for_each   = var.buckets
  bucket     = aws_s3_bucket.this[each.key].id
  depends_on = [aws_s3_bucket_versioning.this]

  rule {
    id     = "tiering"
    status = "Enabled"
    filter {}

    dynamic "transition" {
      for_each = each.value.archive_after_days > 0 ? [1] : []
      content {
        days          = each.value.archive_after_days
        storage_class = "STANDARD_IA"
      }
    }
    dynamic "transition" {
      for_each = each.value.archive_after_days > 0 ? [1] : []
      content {
        days          = each.value.archive_after_days + 60
        storage_class = "GLACIER_IR"
      }
    }
    dynamic "expiration" {
      for_each = each.value.expire_after_days > 0 ? [1] : []
      content {
        days = each.value.expire_after_days
      }
    }
    noncurrent_version_expiration {
      noncurrent_days = each.value.object_lock ? max(each.value.retention_days, 30) : 30
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

resource "aws_s3_bucket_cors_configuration" "this" {
  for_each = { for k, v in var.buckets : k => v if length(v.cors_origins) > 0 }
  bucket   = aws_s3_bucket.this[each.key].id
  cors_rule {
    allowed_methods = ["GET", "PUT", "HEAD"]
    allowed_origins = each.value.cors_origins
    allowed_headers = ["*"]
    max_age_seconds = 3600
  }
}

data "aws_iam_policy_document" "tls_only" {
  for_each = var.buckets
  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.this[each.key].arn, "${aws_s3_bucket.this[each.key].arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  for_each   = var.buckets
  bucket     = aws_s3_bucket.this[each.key].id
  policy     = data.aws_iam_policy_document.tls_only[each.key].json
  depends_on = [aws_s3_bucket_public_access_block.this]
}
