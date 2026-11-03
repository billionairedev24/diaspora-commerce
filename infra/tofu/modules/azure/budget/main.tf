# Azure budget (S182): a monthly consumption budget on the environment's resource group (context.scope), which holds
# every resource of the environment, with Actual and Forecasted notifications to notification_emails and the
# subscription Owners. The amount is in the billing account currency (`currency` is ignored on Azure).
terraform {
  required_version = ">= 1.8"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = ">= 4.0, < 6.0" }
  }
}

locals {
  name = "${var.context.name}-monthly"
  # Azure allows at most five notifications per budget.
  notifications = slice(concat(
    [for t in var.actual_thresholds : { threshold = t, type = "Actual" }],
    [for t in var.forecast_thresholds : { threshold = t, type = "Forecasted" }],
  ), 0, min(5, length(var.actual_thresholds) + length(var.forecast_thresholds)))
}

# The id is built rather than read: the resource group is created in the same apply (envs/*/main.tf), and the
# context.scope reference to it orders this module after it.
data "azurerm_client_config" "this" {}

locals {
  resource_group_id = "/subscriptions/${data.azurerm_client_config.this.subscription_id}/resourceGroups/${var.context.scope}"
}

resource "azurerm_consumption_budget_resource_group" "this" {
  name              = local.name
  resource_group_id = local.resource_group_id
  amount            = var.monthly_limit
  time_grain        = "Monthly"

  time_period {
    # Budgets start on the first day of a month; the start is fixed at creation (ignore_changes below).
    start_date = formatdate("YYYY-MM-01'T'00:00:00Z", timestamp())
  }

  dynamic "notification" {
    for_each = local.notifications
    content {
      enabled        = true
      threshold      = notification.value.threshold
      threshold_type = notification.value.type
      operator       = "GreaterThanOrEqualTo"
      contact_emails = var.notification_emails
      contact_roles  = ["Owner"]
    }
  }

  lifecycle {
    ignore_changes = [time_period]
  }
}
