# One-time, per GCP project: remote state bucket (versioned, uniform access, public access prevented).
# The GCS backend locks natively.
#   cd infra/tofu/bootstrap/gcp && tofu init && tofu apply -var project_id=<project> -var region=us-east4
terraform {
  required_version = ">= 1.8"
  required_providers {
    google = { source = "hashicorp/google", version = ">= 7.0, < 9.0" }
  }
}

variable "project_id" {
  type = string
}

variable "region" {
  type    = string
  default = "us-east4"
}

provider "google" {
  project = var.project_id
  region  = var.region
}

resource "google_storage_bucket" "state" {
  name                        = "afrotransact-tfstate-${var.project_id}"
  location                    = upper(var.region)
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  labels                      = { env = "shared", owner = "platform", cost-center = "engineering", service = "terraform-state" }
  versioning {
    enabled = true
  }
  lifecycle_rule {
    condition {
      num_newer_versions = 50
    }
    action {
      type = "Delete"
    }
  }
  lifecycle {
    prevent_destroy = true
  }
}

output "state_bucket" {
  value = google_storage_bucket.state.name
}
