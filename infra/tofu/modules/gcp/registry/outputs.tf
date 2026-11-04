output "registry_url" {
  description = "Prefix; image = <registry_url>/<service>:<tag>."
  value       = "${local.host}/${local.project}/${local.repo}"
}

output "repository_urls" {
  value = { for r in var.repositories : r => "${local.host}/${local.project}/${local.repo}/${r}" }
}

output "registry" {
  description = "Handle for github-oidc (deploy access)."
  value = {
    cloud      = "gcp"
    host       = local.host
    url        = "${local.host}/${local.project}/${local.repo}"
    project    = local.project
    location   = var.context.region
    repository = google_artifact_registry_repository.this.name
  }
}
