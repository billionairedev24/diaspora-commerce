output "budget_id" {
  description = "Budget identifier (the first alert; OVH has one alert object per threshold and recipient)."
  value       = try(values(ovh_cloud_project_alerting.this)[0].id, "")
}

output "budget_name" {
  description = "Budget name as shown in the billing console."
  value       = "${var.context.name}-budget"
}

output "alert_target" {
  description = "Where alerts are published: the recipient emails."
  value       = join(",", local.emails)
}
