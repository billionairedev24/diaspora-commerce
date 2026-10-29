# One-time, per AWS account: remote state bucket (versioned, KMS, TLS-only, public access blocked).
# Locking uses S3 native lockfiles (use_lockfile = true), so no DynamoDB table is needed.
#   cd infra/tofu/bootstrap/aws && tofu init && tofu apply -var region=us-east-1
# State of this root stays local (commit nothing; keep the file in a safe place) or migrate it into the
# bucket afterwards with a backend "s3" block.
terraform {
  required_version = ">= 1.8"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "tags" {
  type    = map(string)
  default = { env = "shared", owner = "platform", "cost-center" = "engineering", service = "terraform-state" }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = var.tags
  }
}

data "aws_caller_identity" "this" {}

resource "aws_kms_key" "state" {
  description         = "OpenTofu state encryption"
  enable_key_rotation = true
}

resource "aws_s3_bucket" "state" {
  bucket = "afrotransact-tfstate-${data.aws_caller_identity.this.account_id}"
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.state.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket                  = aws_s3_bucket.state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

data "aws_iam_policy_document" "tls_only" {
  statement {
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.state.arn, "${aws_s3_bucket.state.arn}/*"]
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

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.tls_only.json
}

output "state_bucket" {
  value = aws_s3_bucket.state.bucket
}
