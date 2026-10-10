# GCP secrets: one Secret Manager secret per service, id <prefix>-<env>-<service> (Secret Manager ids
# cannot contain "/"), JSON payload, automatic replication, encrypted at rest by Google (CMEK optional).
terraform {
  required_version = ">= 1.8"
  required_providers {
    google = { source = "hashicorp/google", version = ">= 7.0, < 9.0" }
  }
}

locals {
  project = var.context.scope
  prefix  = "${var.prefix}-${var.context.environment}"
  labels = { for k, v in merge(var.context.tags, { module = "secrets" }) :
  lower(replace(k, "/[^a-zA-Z0-9_-]/", "_")) => lower(replace(v, "/[^a-zA-Z0-9_-]/", "-")) }
}

resource "google_secret_manager_secret" "service" {
  for_each  = toset(var.services)
  project   = local.project
  secret_id = "${local.prefix}-${each.key}"
  labels    = merge(local.labels, { service = each.key })
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "seed" {
  for_each    = toset(var.services)
  secret      = google_secret_manager_secret.service[each.key].id
  secret_data = jsonencode(lookup(var.initial_values, each.key, {}))
  lifecycle {
    ignore_changes = [secret_data]
  }
}
