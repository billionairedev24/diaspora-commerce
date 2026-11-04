# OVHcloud has no budget object, only Public Cloud consumption alerts: one alert per actual threshold (percent of
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
  alerts = {
    for pair in setproduct(local.emails, var.actual_thresholds) :
    "${pair[0]}-${pair[1]}" => { email = pair[0], percent = pair[1] }
  }
}

resource "ovh_cloud_project_alerting" "this" {
  for_each          = local.alerts
  service_name      = var.context.scope
  email             = each.value.email
  monthly_threshold = ceil(var.monthly_limit * each.value.percent / 100)
  delay             = 3600
}
