output "registry_url" {
  description = "Prefix; image = <registry_url>/<service>:<tag>."
  value       = "${local.host}/${local.namespace}"
}

output "repository_urls" {
  value = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}

output "registry" {
  description = "Handle for github-oidc (deploy access)."
  value = {
    cloud           = "aws"
    host            = local.host
    url             = "${local.host}/${local.namespace}"
    repository_arns = [for r in aws_ecr_repository.this : r.arn]
  }
}
