output "budget_id" {
  description = "Budget identifier."
  value       = azurerm_consumption_budget_resource_group.this.id
}

output "budget_name" {
  description = "Budget name as shown in the billing console."
  value       = azurerm_consumption_budget_resource_group.this.name
}

output "alert_target" {
  description = "Where alerts are published besides email: the SNS topic ARN (aws), notification channel ids (gcp), the resource group id (azure)."
  value       = local.resource_group_id
}
