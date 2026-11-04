# OVHcloud has no workload identity for pods, so each service that touches a bucket gets its own Object Storage user (free)
# with an S3 policy limited to exactly its buckets. The access key pair is stored in the Secret Manager at
# <prefix>/<env>/<service>-storage and reaches the pod through the service's ExternalSecret (S3_ACCESS_KEY_ID,
# S3_SECRET_ACCESS_KEY, S3_ENDPOINT). The key pairs are also in the OpenTofu state (sensitive): keep the state bucket private.
# Services without buckets get no identity; External Secrets Operator authenticates with its own mTLS certificate (secrets module).
terraform {
  required_version = ">= 1.8"
  required_providers {
    ovh = { source = "ovh/ovh", version = ">= 2.0, < 3.0" }
  }
}

locals {
  with_buckets = { for k, v in var.services : k => v if length(v.buckets) > 0 }
  actions = [
    "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket", "s3:GetBucketLocation",
    "s3:ListMultipartUploadParts", "s3:ListBucketMultipartUploads", "s3:AbortMultipartUpload",
  ]
}

resource "ovh_cloud_project_user" "this" {
  for_each     = local.with_buckets
  service_name = var.context.scope
  description  = "${var.context.name}-${each.key} object storage"
  role_names   = ["objectstore_operator"]
}

resource "ovh_cloud_project_user_s3_credential" "this" {
  for_each     = local.with_buckets
  service_name = var.context.scope
  user_id      = ovh_cloud_project_user.this[each.key].id
}

resource "ovh_cloud_project_user_s3_policy" "this" {
  for_each     = local.with_buckets
  service_name = var.context.scope
  user_id      = ovh_cloud_project_user.this[each.key].id
  policy = jsonencode({
    Statement = [{
      Sid    = "ServiceBuckets"
      Effect = "Allow"
      Action = local.actions
      Resource = flatten([
        for b in each.value.buckets : [
          "arn:aws:s3:::${var.buckets[b].name}",
          "arn:aws:s3:::${var.buckets[b].name}/*",
        ]
      ])
    }]
  })
}

resource "ovh_okms_secret" "storage" {
  for_each = var.secret_store == null ? {} : local.with_buckets
  okms_id  = var.secret_store.okms_id
  path     = "${var.secret_store.path}/${var.context.environment}/${each.key}-storage"
  version = {
    data = jsonencode({
      S3_ACCESS_KEY_ID     = ovh_cloud_project_user_s3_credential.this[each.key].access_key_id
      S3_SECRET_ACCESS_KEY = ovh_cloud_project_user_s3_credential.this[each.key].secret_access_key
      S3_ENDPOINT          = "https://s3.${lower(regex("^[A-Za-z]+", var.context.region))}.io.cloud.ovh.net"
    })
  }
}
