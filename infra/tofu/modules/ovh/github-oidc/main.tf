# OVHcloud cannot federate GitHub's OIDC token, so this module creates NOTHING on OVH: there is no keyless identity to grant.
# The three CI identities are created by hand once (they bootstrap tofu itself) and kept as GitHub secrets, see
# docs/engineering/ci-cd.md#ovhcloud:
#   deploy - Harbor robot account on the registry project, push + pull only (OVH_REGISTRY_USERNAME / OVH_REGISTRY_PASSWORD)
#   plan   - API credentials with read-only rules + an Object Storage user that can read the state bucket (OVH_PLAN_*)
#   apply  - API credentials limited to /cloud/project/<id>/* + the state Object Storage user (OVH_APPLY_*, <env>-infra)
# The outputs keep the interface and tell the workflows which names to read.
terraform {
  required_version = ">= 1.8"
  required_providers {
    ovh = { source = "ovh/ovh", version = ">= 2.0, < 3.0" }
  }
}

locals {
  identity = upper(var.access)
}
