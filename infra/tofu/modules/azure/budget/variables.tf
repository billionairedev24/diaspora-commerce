variable "monthly_limit" {
  description = "Monthly cloud budget for this environment in `currency` (compute, data stores, storage, network, registry). SaaS (Elastic Cloud, Confluent Cloud, Temporal Cloud, Cloudflare, Grafana Cloud) is budgeted in each vendor's console."
  type        = number
  validation {
    condition     = var.monthly_limit > 0
    error_message = "monthly_limit must be positive."
  }
}

variable "currency" {
  description = "Budget currency (AWS and GCP; Azure always uses the billing account currency)."
  type        = string
  default     = "USD"
}

variable "actual_thresholds" {
  description = "Alert when ACTUAL month-to-date spend reaches these percentages of the limit."
  type        = list(number)
  default     = [50, 80, 100]
}

variable "forecast_thresholds" {
  description = "Alert when FORECASTED month-end spend reaches these percentages of the limit."
  type        = list(number)
  default     = [100]
}

variable "notification_emails" {
  description = "Recipients of budget alerts. Empty = AWS: the SNS topic only (subscribe a chat/pager integration), GCP: billing account admins, Azure: subscription Owners."
  type        = list(string)
  default     = []
}

variable "shared_account" {
  description = "Several environments share the AWS account / GCP project: true filters the budget to this environment (AWS: cost allocation tag `env`, activated once in Billing; GCP: label env). Azure budgets are always scoped to the environment's resource group."
  type        = bool
  default     = false
}

variable "billing_account" {
  description = "GCP billing account id (XXXXXX-XXXXXX-XXXXXX) the project is linked to. Required on GCP, ignored on AWS and Azure."
  type        = string
  default     = ""
}
