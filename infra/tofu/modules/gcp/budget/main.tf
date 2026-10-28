# GCP budget (S182): a Cloud Billing budget on the environment's project (context.scope) with CURRENT_SPEND and
# FORECASTED_SPEND threshold rules. Alerts go to billing account admins and to one Cloud Monitoring email channel per
# notification_emails entry. Needs the Cloud Billing Budget API (billingbudgets.googleapis.com) on the project and
# `roles/billing.costsManager` on the billing account for the identity that applies (the `apply` GitHub identity or
# an admin; the first apply of an environment is done by an admin, see docs/runbooks/first-environment.md).
terraform {
  required_version = ">= 1.8"
  required_providers {
    google = { source = "hashicorp/google", version = ">= 7.0, < 9.0" }
  }
}

locals {
  project = var.context.scope
  name    = "${var.context.name}-monthly"
}

data "google_project" "this" {
  project_id = local.project
}

resource "google_project_service" "billingbudgets" {
  project            = local.project
  service            = "billingbudgets.googleapis.com"
  disable_on_destroy = false
}

resource "google_monitoring_notification_channel" "email" {
  for_each     = toset(var.notification_emails)
  project      = local.project
  display_name = "${local.name} budget: ${each.key}"
  type         = "email"
  labels       = { email_address = each.key }
}

resource "google_billing_budget" "this" {
  billing_account = var.billing_account
  display_name    = local.name

  budget_filter {
    projects               = ["projects/${data.google_project.this.number}"]
    labels                 = var.shared_account ? { env = var.context.environment } : {}
    calendar_period        = "MONTH"
    credit_types_treatment = "INCLUDE_ALL_CREDITS"
  }

  amount {
    specified_amount {
      currency_code = var.currency
      units         = tostring(floor(var.monthly_limit))
    }
  }

  dynamic "threshold_rules" {
    for_each = var.actual_thresholds
    content {
      threshold_percent = threshold_rules.value / 100
      spend_basis       = "CURRENT_SPEND"
    }
  }

  dynamic "threshold_rules" {
    for_each = var.forecast_thresholds
    content {
      threshold_percent = threshold_rules.value / 100
      spend_basis       = "FORECASTED_SPEND"
    }
  }

  all_updates_rule {
    monitoring_notification_channels = [for c in google_monitoring_notification_channel.email : c.id]
    disable_default_iam_recipients   = false
  }

  lifecycle {
    precondition {
      condition     = can(regex("^[0-9A-F]{6}-[0-9A-F]{6}-[0-9A-F]{6}$", var.billing_account))
      error_message = "billing_account (gcp_billing_account in terraform.tfvars) must be the project's billing account id, e.g. 01ABCD-23EF45-6789AB."
    }
  }

  depends_on = [google_project_service.billingbudgets]
}
