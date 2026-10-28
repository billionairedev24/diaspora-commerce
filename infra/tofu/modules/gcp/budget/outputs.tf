output "budget_id" {
  description = "Budget identifier."
  value       = google_billing_budget.this.id
}

output "budget_name" {
  description = "Budget name as shown in the billing console."
  value       = google_billing_budget.this.display_name
}

output "alert_target" {
  description = "Where alerts are published besides email: the SNS topic ARN (aws), notification channel ids (gcp), the resource group id (azure)."
  value       = join(",", [for c in google_monitoring_notification_channel.email : c.id])
}
