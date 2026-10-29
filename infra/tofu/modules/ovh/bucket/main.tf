# OVHcloud Object Storage (S3-compatible, Standard class), created through the OVH API so no S3 credentials are needed to plan.
#   - versioning, SSE (AES256) and WORM Object Lock (COMPLIANCE when deletion_protection, GOVERNANCE otherwise; the retention
#     is the default rule for new objects) as in the other clouds
#   - cost: incomplete multipart uploads are aborted after 3 days, noncurrent versions expire after 30 days (or the
#     retention if longer), archive_after_days moves objects to Standard-Infrequent-Access, expire_after_days deletes them.
#     Object Storage egress and requests are not billed for the first part of the usage; check the price page for your volume.
#   - cors_origins: the OVH provider has no CORS resource. Browsers upload through the media service's signed URLs: set the
#     rules once with `aws s3api put-bucket-cors --endpoint-url <endpoint>` (docs/engineering/cloud-cost.md, OVHcloud)
#     or keep uploads server-side. The origins are echoed in the bucket tags for the runbook.
# network is unused (buckets are reached over the public S3 endpoint with per-service credentials, see the identity module).
terraform {
  required_version = ">= 1.8"
  required_providers {
    ovh = { source = "ovh/ovh", version = ">= 2.0, < 3.0" }
  }
}

locals {
  site = upper(regex("^[A-Za-z]+", var.context.region))
  # Standard-IA needs objects of at least 128 KiB to be worthwhile; the lifecycle rule applies to all objects as in the
  # other clouds (cheaper storage, slightly dearer reads).
  lifecycle = {
    for k, b in var.buckets : k => b if b.archive_after_days > 0 || b.expire_after_days > 0 || b.versioning
  }
}

resource "ovh_cloud_project_storage" "this" {
  for_each     = var.buckets
  service_name = var.context.scope
  region_name  = local.site
  name         = "${var.context.name}-${each.key}"

  versioning = {
    status = each.value.versioning ? "enabled" : "suspended"
  }

  encryption = {
    sse_algorithm = "AES256"
  }

  object_lock = each.value.object_lock ? {
    status = "enabled"
    rule = {
      mode   = var.context.deletion_protection ? "compliance" : "governance"
      period = "P${max(each.value.retention_days, 1)}D"
    }
  } : null

  tags = merge(var.context.tags, {
    service = "platform"
    module  = "bucket"
    cors    = join(",", each.value.cors_origins)
  })
}

resource "ovh_cloud_project_storage_object_bucket_lifecycle_configuration" "this" {
  for_each       = local.lifecycle
  service_name   = var.context.scope
  region_name    = local.site
  container_name = ovh_cloud_project_storage.this[each.key].name

  rules = concat(
    [{
      id     = "abort-incomplete-uploads"
      status = "enabled"
      abort_incomplete_multipart_upload = {
        days_after_initiation = 3
      }
    }],
    each.value.versioning ? [{
      id     = "expire-noncurrent-versions"
      status = "enabled"
      noncurrent_version_expiration = {
        noncurrent_days = max(30, each.value.retention_days)
      }
    }] : [],
    each.value.archive_after_days > 0 ? [{
      id     = "to-infrequent-access"
      status = "enabled"
      transitions = [{
        days          = each.value.archive_after_days
        storage_class = "STANDARD_IA"
      }]
    }] : [],
    each.value.expire_after_days > 0 ? [{
      id     = "expire-objects"
      status = "enabled"
      expiration = {
        days = each.value.expire_after_days
      }
    }] : [],
  )
}
