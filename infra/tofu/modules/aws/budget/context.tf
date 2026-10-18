# Shared module contract input. This file is copied verbatim into every cloud module
# (infra/tofu/scripts/sync-interface.sh) so all three module sets accept the same context.
variable "context" {
  description = <<-EOT
    Naming, placement and tagging shared by every module.
      name        - resource name prefix, e.g. "afrotransact-dev" (lowercase, dashes)
      environment - dev | uat | prod
      region      - cloud region (us-east-1 | us-east4 | eastus ...)
      scope       - GCP project id | Azure resource group name | unused on AWS
      tags        - must contain env, owner and cost-center (enforced by infra/policy)
      admin_cidrs - CIDRs allowed to reach management endpoints (cluster API, Key Vault)
      deletion_protection - protect stateful resources from destroy (always true in prod)
  EOT
  type = object({
    name                = string
    environment         = string
    region              = string
    scope               = optional(string, "")
    tags                = map(string)
    admin_cidrs         = optional(list(string), [])
    deletion_protection = optional(bool, true)
  })

  validation {
    condition     = contains(["dev", "uat", "prod"], var.context.environment)
    error_message = "context.environment must be dev, uat or prod."
  }
  validation {
    condition     = alltrue([for k in ["env", "owner", "cost-center"] : contains(keys(var.context.tags), k)])
    error_message = "context.tags must include env, owner and cost-center."
  }
}
