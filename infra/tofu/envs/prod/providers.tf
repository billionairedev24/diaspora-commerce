# Provider configuration. IDENTICAL in every env.
# Every provider uses for_each (OpenTofu >= 1.9) over local.on.<cloud>: the selected cloud gets exactly one
# instance ("aws" | "gcp" | "azure" | "ovh"), the others get none and are never configured - so `tofu plan` for
# one cloud never needs credentials for the others. Credentials come from the environment (OIDC in CI:
# the login actions export AWS_* / GOOGLE_* / ARM_* / OVH_* (OVHcloud has no OIDC: scoped API keys); EC_API_KEY; CONFLUENT_CLOUD_API_KEY/_SECRET; TEMPORAL_CLOUD_API_KEY).
# Switching an existing environment to another cloud = destroy, flip `cloud`, apply (data migration runbook).

provider "aws" {
  alias    = "this"
  for_each = local.on.aws
  region   = var.region
  default_tags {
    tags = local.context.tags
  }
}

provider "google" {
  alias    = "this"
  for_each = local.on.gcp
  project  = var.gcp_project_id
  region   = var.region
  default_labels = {
    for k, v in local.context.tags :
    lower(replace(k, "/[^a-zA-Z0-9_-]/", "_")) => lower(replace(v, "/[^a-zA-Z0-9_-]/", "-"))
  }
}

provider "azurerm" {
  alias    = "this"
  for_each = local.on.azure
  features {
    key_vault {
      purge_soft_delete_on_destroy = false
    }
    resource_group {
      prevent_deletion_if_contains_resources = true
    }
  }
  subscription_id                 = var.azure_subscription_id
  tenant_id                       = var.azure_tenant_id
  storage_use_azuread             = true
  resource_provider_registrations = "core"
}

provider "ovh" {
  alias    = "this"
  for_each = local.on.ovh
  endpoint = var.ovh_endpoint
  # application_key / application_secret / consumer_key come from OVH_APPLICATION_KEY / _SECRET / OVH_CONSUMER_KEY
}

provider "ec" {
  alias    = "this"
  for_each = local.on.ec
}

provider "confluent" {
  alias    = "this"
  for_each = local.on.confluent
}

provider "temporalcloud" {
  alias    = "this"
  for_each = local.on.temporal
}
