# OVHcloud Managed Private Registry (Harbor). SMALL plan everywhere: the cheapest tier is enough for ~25 service images with
# the `keep_last` cleanup (upgrade to MEDIUM/LARGE only when storage or parallel pulls say so; the plan is a one-line change).
# Images live in the Harbor project "library" (the OVH provider cannot create Harbor projects). Tag immutability and the
# retention policy (keep_last, untagged purge) are Harbor project rules: set them once in the Harbor UI or with the Harbor
# provider; the deploy workflow only ever pushes new sha tags. Pulling needs an image pull secret (no node identity on OVH),
# created from the Harbor robot account that also feeds the deploy workflow (docs/engineering/ci-cd.md#ovhcloud).
terraform {
  required_version = ">= 1.8"
  required_providers {
    ovh = { source = "ovh/ovh", version = ">= 2.0, < 3.0" }
  }
}

locals {
  site      = upper(regex("^[A-Za-z]+", var.context.region))
  namespace = "library"
}

data "ovh_cloud_project_capabilities_containerregistry_filter" "small" {
  service_name = var.context.scope
  region       = local.site
  plan_name    = "SMALL"
}

resource "ovh_cloud_project_containerregistry" "this" {
  service_name = var.context.scope
  name         = var.context.name
  region       = local.site
  plan_id      = data.ovh_cloud_project_capabilities_containerregistry_filter.small.id
}
