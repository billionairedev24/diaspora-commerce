# GCP buckets: uniform bucket-level access, public access prevention enforced, Google-managed encryption
# at rest (CMEK optional), versioning, retention policy (locked when deletion_protection) for KYC evidence,
# lifecycle to Nearline/Coldline, regional location.
terraform {
  required_version = ">= 1.8"
  required_providers {
    google = { source = "hashicorp/google", version = ">= 7.0, < 9.0" }
    random = { source = "hashicorp/random", version = ">= 3.6" }
  }
}

locals {
  project = var.context.scope
  labels = { for k, v in merge(var.context.tags, { service = "platform", module = "bucket" }) :
  lower(replace(k, "/[^a-zA-Z0-9_-]/", "_")) => lower(replace(v, "/[^a-zA-Z0-9_-]/", "-")) }
}

resource "random_id" "suffix" {
  byte_length = 3
}

resource "google_storage_bucket" "this" {
  for_each                    = var.buckets
  project                     = local.project
  name                        = "${var.context.name}-${each.key}-${random_id.suffix.hex}"
  location                    = upper(var.context.region)
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = !var.context.deletion_protection && !each.value.object_lock
  labels                      = merge(local.labels, { bucket = each.key })

  versioning {
    enabled = each.value.versioning || each.value.object_lock
  }

  dynamic "retention_policy" {
    for_each = each.value.object_lock ? [1] : []
    content {
      retention_period = max(each.value.retention_days, 1) * 86400
      is_locked        = var.context.deletion_protection
    }
  }

  dynamic "lifecycle_rule" {
    for_each = each.value.archive_after_days > 0 ? [1] : []
    content {
      condition {
        age = each.value.archive_after_days
      }
      action {
        type          = "SetStorageClass"
        storage_class = "NEARLINE"
      }
    }
  }

  dynamic "lifecycle_rule" {
    for_each = each.value.archive_after_days > 0 ? [1] : []
    content {
      condition {
        age = each.value.archive_after_days + 60
      }
      action {
        type          = "SetStorageClass"
        storage_class = "COLDLINE"
      }
    }
  }

  dynamic "lifecycle_rule" {
    for_each = each.value.expire_after_days > 0 ? [1] : []
    content {
      condition {
        age = each.value.expire_after_days
      }
      action {
        type = "Delete"
      }
    }
  }

  lifecycle_rule {
    condition {
      days_since_noncurrent_time = each.value.object_lock ? max(each.value.retention_days, 30) : 30
    }
    action {
      type = "Delete"
    }
  }

  dynamic "cors" {
    for_each = length(each.value.cors_origins) > 0 ? [1] : []
    content {
      origin          = each.value.cors_origins
      method          = ["GET", "PUT", "HEAD"]
      response_header = ["*"]
      max_age_seconds = 3600
    }
  }
}
