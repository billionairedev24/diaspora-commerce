# GCP GitHub OIDC trust: Workload Identity Federation pool + GitHub provider (once per project), restricted
# to this repository by attribute_condition, and a service account impersonable only by the listed
# subjects (principal://.../subject/<sub>).
#   deploy -> artifactregistry.writer on the platform repository
#   plan   -> roles/viewer (+ state bucket objectViewer granted in bootstrap)
#   apply  -> roles/owner, only from the protected GitHub environment
terraform {
  required_version = ">= 1.8"
  required_providers {
    google = { source = "hashicorp/google", version = ">= 7.0, < 9.0" }
  }
}

data "google_project" "this" {
  project_id = local.project
}

locals {
  project = var.context.scope
  pool_id = var.create_provider ? google_iam_workload_identity_pool.github[0].workload_identity_pool_id : var.provider_ref
  pool    = "projects/${data.google_project.this.number}/locations/global/workloadIdentityPools/${local.pool_id}"
  # Exact subjects are bound directly; wildcard subjects are not supported by WIF principals, so callers
  # must list exact values (environment:dev, pull_request, ref:refs/heads/main ...).
  subjects = [for s in var.subjects : s if !strcontains(s, "*")]
}

resource "google_iam_workload_identity_pool" "github" {
  count                     = var.create_provider ? 1 : 0
  project                   = local.project
  workload_identity_pool_id = "github"
  display_name              = "GitHub Actions"
}

resource "google_iam_workload_identity_pool_provider" "github" {
  count                              = var.create_provider ? 1 : 0
  project                            = local.project
  workload_identity_pool_id          = google_iam_workload_identity_pool.github[0].workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  display_name                       = "GitHub OIDC"
  attribute_mapping = {
    "google.subject"        = "assertion.sub"
    "attribute.repository"  = "assertion.repository"
    "attribute.environment" = "assertion.environment"
    "attribute.ref"         = "assertion.ref"
  }
  attribute_condition = "assertion.repository == \"${var.github_repository}\""
  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account" "this" {
  project      = local.project
  account_id   = substr("gh-${var.context.environment}-${var.access}", 0, 30)
  display_name = "GitHub Actions ${var.access} (${var.context.environment})"
}

resource "google_service_account_iam_member" "federation" {
  for_each           = toset(local.subjects)
  service_account_id = google_service_account.this.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principal://iam.googleapis.com/${local.pool}/subject/${each.value}"
}

resource "google_artifact_registry_repository_iam_member" "deploy" {
  count      = var.access == "deploy" && var.registry != null ? 1 : 0
  project    = local.project
  location   = var.registry.location
  repository = var.registry.repository
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.this.email}"
}

resource "google_project_iam_member" "infra" {
  count   = var.access == "deploy" ? 0 : 1
  project = local.project
  role    = var.access == "plan" ? "roles/viewer" : "roles/owner"
  member  = "serviceAccount:${google_service_account.this.email}"
}
