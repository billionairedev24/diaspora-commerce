# GCP identity: one Google service account per service, bound to the Kubernetes service account through
# Workload Identity (roles/iam.workloadIdentityUser on <project>.svc.id.goog[<ns>/<sa>]).
# Grants: objectAdmin on the listed buckets; secretAccessor on env secrets for External Secrets Operator.
terraform {
  required_version = ">= 1.8"
  required_providers {
    google = { source = "hashicorp/google", version = ">= 7.0, < 9.0" }
  }
}

locals {
  project = var.context.scope
  sa      = { for k, v in var.services : k => coalesce(v.service_account, k) }
  # account_id: 6-30 chars. "at-<env>-<svc>" fits every service in PORTS.md.
  account_id = { for k, _ in var.services : k => substr("at-${var.context.environment}-${k}", 0, 30) }
  bucket_grants = merge([for svc, v in var.services : {
    for b in v.buckets : "${svc}/${b}" => { svc = svc, bucket = var.buckets[b].id }
  }]...)
}

resource "google_service_account" "this" {
  for_each     = var.services
  project      = local.project
  account_id   = local.account_id[each.key]
  display_name = "${each.key} (${var.context.environment})"
  description  = "Workload identity for ${each.value.namespace}/${local.sa[each.key]}"
}

resource "google_service_account_iam_member" "workload_identity" {
  for_each           = var.services
  service_account_id = google_service_account.this[each.key].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.cluster.workload_pool}[${each.value.namespace}/${local.sa[each.key]}]"
}

resource "google_storage_bucket_iam_member" "buckets" {
  for_each = local.bucket_grants
  bucket   = each.value.bucket
  role     = "roles/storage.objectAdmin"
  member   = "serviceAccount:${google_service_account.this[each.value.svc].email}"
}

resource "google_project_iam_member" "secrets" {
  for_each = { for k, v in var.services : k => v if v.secrets }
  project  = local.project
  role     = "roles/secretmanager.secretAccessor"
  member   = "serviceAccount:${google_service_account.this[each.key].email}"
  condition {
    title      = "env-secrets-only"
    expression = "resource.name.startsWith(\"projects/${data.google_project.this.number}/secrets/${var.secret_store.prefix}-\")"
  }
}

data "google_project" "this" {
  project_id = local.project
}
