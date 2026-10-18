# GCP registry: one regional Artifact Registry Docker repository "afrotransact" (images <svc>:<tag>),
# immutable tags, cleanup policy keeping the newest N versions per image, vulnerability scanning on.
terraform {
  required_version = ">= 1.8"
  required_providers {
    google = { source = "hashicorp/google", version = ">= 7.0, < 9.0" }
  }
}

locals {
  project = var.context.scope
  repo    = "afrotransact"
  host    = "${var.context.region}-docker.pkg.dev"
  labels = { for k, v in merge(var.context.tags, { service = "platform", module = "registry" }) :
  lower(replace(k, "/[^a-zA-Z0-9_-]/", "_")) => lower(replace(v, "/[^a-zA-Z0-9_-]/", "-")) }
}

resource "google_artifact_registry_repository" "this" {
  project       = local.project
  location      = var.context.region
  repository_id = local.repo
  format        = "DOCKER"
  description   = "AfroTransact service images (mirrored from GHCR): ${join(", ", var.repositories)}"
  labels        = local.labels

  docker_config {
    immutable_tags = var.immutable_tags
  }

  cleanup_policy_dry_run = false
  cleanup_policies {
    id     = "keep-newest"
    action = "KEEP"
    most_recent_versions {
      keep_count = var.keep_last
    }
  }
  cleanup_policies {
    id     = "delete-old"
    action = "DELETE"
    condition {
      older_than = "7776000s"
    }
  }

  vulnerability_scanning_config {
    enablement_config = "INHERITED"
  }
}

resource "google_artifact_registry_repository_iam_member" "readers" {
  for_each   = toset(var.reader_principals)
  project    = local.project
  location   = var.context.region
  repository = google_artifact_registry_repository.this.name
  role       = "roles/artifactregistry.reader"
  member     = each.value
}
