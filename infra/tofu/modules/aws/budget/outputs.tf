output "budget_id" {
  description = "Budget identifier."
  value       = aws_budgets_budget.this.id
}

output "budget_name" {
  description = "Budget name as shown in the billing console."
  value       = aws_budgets_budget.this.name
}

output "alert_target" {
  description = "Where alerts are published besides email: the SNS topic ARN (aws), notification channel ids (gcp), the resource group id (azure)."
  value       = aws_sns_topic.alerts.arn
}
