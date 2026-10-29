# One-time, per OVHcloud Public Cloud project: the remote-state bucket (versioned, SSE, private) and a state-only
# Object Storage user whose S3 policy reaches nothing but that bucket. OVH Object Storage has no native lock, so the
# tofu.yml concurrency group is the lock (docs/engineering/ci-cd.md#ovhcloud).
#   export OVH_APPLICATION_KEY=... OVH_APPLICATION_SECRET=... OVH_CONSUMER_KEY=...
#   cd infra/tofu/bootstrap/ovh && tofu init && tofu apply -var service_name=<project id> -var region=GRA
#   tofu output state            # TF_TARGETS.<env>.state
#   tofu output -raw s3_access_key; tofu output -raw s3_secret_key    # OVH_PLAN_/OVH_APPLY_ S3 secrets (use two users in practice)
# State of this root stays local (keep the file safe) or migrate it into the bucket afterwards.
terraform {
  required_version = ">= 1.8"
  required_providers {
    ovh = { source = "ovh/ovh", version = ">= 2.0, < 3.0" }
  }
}

variable "service_name" {
  description = "Public Cloud project id."
  type        = string
}

variable "region" {
  description = "Object Storage region (GRA, RBX, SBG, BHS, DE, UK, WAW ...)."
  type        = string
  default     = "GRA"
}

variable "endpoint" {
  type    = string
  default = "ovh-eu"
}

provider "ovh" {
  endpoint = var.endpoint
}

locals {
  bucket = "afrotransact-tfstate-${substr(var.service_name, 0, 8)}"
}

resource "ovh_cloud_project_storage" "state" {
  service_name = var.service_name
  region_name  = var.region
  name         = local.bucket
  versioning = {
    status = "enabled"
  }
  encryption = {
    sse_algorithm = "AES256"
  }
  tags = { env = "shared", owner = "platform", "cost-center" = "engineering", service = "terraform-state" }

  lifecycle {
    prevent_destroy = true
  }
}

# Old state versions are only for recovery: keep 90 days, drop stale uploads.
resource "ovh_cloud_project_storage_object_bucket_lifecycle_configuration" "state" {
  service_name   = var.service_name
  region_name    = var.region
  container_name = ovh_cloud_project_storage.state.name
  rules = [{
    id     = "expire-old-state-versions"
    status = "enabled"
    noncurrent_version_expiration = {
      noncurrent_days = 90
    }
    abort_incomplete_multipart_upload = {
      days_after_initiation = 3
    }
  }]
}

resource "ovh_cloud_project_user" "state" {
  service_name = var.service_name
  description  = "afrotransact opentofu state"
  role_names   = ["objectstore_operator"]
}

resource "ovh_cloud_project_user_s3_credential" "state" {
  service_name = var.service_name
  user_id      = ovh_cloud_project_user.state.id
}

resource "ovh_cloud_project_user_s3_policy" "state" {
  service_name = var.service_name
  user_id      = ovh_cloud_project_user.state.id
  policy = jsonencode({
    Statement = [{
      Sid    = "StateBucketOnly"
      Effect = "Allow"
      Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket", "s3:GetBucketLocation"]
      Resource = [
        "arn:aws:s3:::${local.bucket}",
        "arn:aws:s3:::${local.bucket}/*",
      ]
    }]
  })
}

output "state" {
  description = "Value of TF_TARGETS.<env>.state."
  value       = { bucket = ovh_cloud_project_storage.state.name, region = lower(var.region) }
}

output "s3_access_key" {
  value = ovh_cloud_project_user_s3_credential.state.access_key_id
}

output "s3_secret_key" {
  value     = ovh_cloud_project_user_s3_credential.state.secret_access_key
  sensitive = true
}
