# OVHcloud has no budget object, only Public Cloud consumption alerts: one alert per email at the LOWEST actual threshold (percent of
# monthly_limit, in the account currency), sent by email. Forecast thresholds and `currency` have no OVH equivalent and
# are ignored. Without notification_emails the account email is used. OVH alerts are per project: when several
# environments share a project, each module instance adds its own thresholds (the lowest one to fire is the project's).
terraform {
  required_version = ">= 1.8"
  required_providers {
    ovh = { source = "ovh/ovh", version = ">= 2.0, < 3.0" }
  }
}

data "ovh_me" "this" {}

locals {
  emails = length(var.notification_emails) > 0 ? var.notification_emails : [data.ovh_me.this.email]
  # OVH accepts ONE consumption alert per project and email: a second one is refused with HTTP 500 (seen on the OVH US
  # API, 2026-11-28). So only the LOWEST actual threshold becomes an alert; the others are ignored on OVH.
  first_threshold = min(var.actual_thresholds...)
  alerts = {
    for email in local.emails :
    "${email}-${local.first_threshold}" => { email = email, percent = local.first_threshold }
  }
}

resource "ovh_cloud_project_alerting" "this" {
  for_each          = local.alerts
  service_name      = var.context.scope
  email             = each.value.email
  monthly_threshold = ceil(var.monthly_limit * each.value.percent / 100)
  delay             = 3600
}
