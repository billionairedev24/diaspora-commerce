output "login" {
  description = "Inputs for google-github-actions/auth (store as GitHub environment variables)."
  value = {
    cloud                      = "gcp"
    workload_identity_provider = "${local.pool}/providers/github"
    service_account            = google_service_account.this.email
    project                    = local.project
  }
}

output "principal" {
  value = google_service_account.this.email
}

output "provider_ref" {
  description = "Pass to other instances with create_provider = false."
  value       = local.pool_id
}
