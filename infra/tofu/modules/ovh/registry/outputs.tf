locals {
  host = trimprefix(ovh_cloud_project_containerregistry.this.url, "https://")
}

output "registry_url" {
  description = "Prefix; image = <registry_url>/<service>:<tag>."
  value       = "${local.host}/${local.namespace}"
}

output "repository_urls" {
  value = { for r in var.repositories : r => "${local.host}/${local.namespace}/${r}" }
}

output "registry" {
  description = "Handle for github-oidc (deploy access)."
  value = {
    cloud = "ovh"
    host  = local.host
    url   = "${local.host}/${local.namespace}"
    id    = ovh_cloud_project_containerregistry.this.id
    name  = ovh_cloud_project_containerregistry.this.name
  }
}
