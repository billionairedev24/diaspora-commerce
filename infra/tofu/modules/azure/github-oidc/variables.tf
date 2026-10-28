variable "github_repository" {
  description = "owner/repo allowed to federate."
  type        = string
  default     = "billionairedev24/diaspora-commerce"
}

variable "subjects" {
  description = "Allowed OIDC `sub` claims, e.g. repo:owner/repo:environment:prod, repo:owner/repo:pull_request."
  type        = list(string)
}

variable "access" {
  description = <<-EOT
    deploy - push images to the registry (mirror step of _deploy.yml)
    plan   - read-only, for `tofu plan` on pull requests
    apply  - manage infrastructure (gated by the GitHub environment)
  EOT
  type        = string
  validation {
    condition     = contains(["deploy", "plan", "apply"], var.access)
    error_message = "access must be deploy, plan or apply."
  }
}

variable "create_provider" {
  description = "Create the account/project-wide GitHub OIDC provider/pool (only one instance per account or project should)."
  type        = bool
  default     = true
}

variable "provider_ref" {
  description = "Existing provider ARN (aws) / pool id (gcp) when create_provider = false. Unused on Azure."
  type        = string
  default     = ""
}

variable "registry" {
  description = "The `registry` output of the registry module (deploy access is scoped to it)."
  type        = any
  default     = null
}
