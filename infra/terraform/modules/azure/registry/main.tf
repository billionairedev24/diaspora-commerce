# Azure registry: ACR (Premium in prod for zone redundancy + retention; Standard elsewhere), admin user off,
# anonymous pull off, Entra-only auth. Images live under afrotransact/<service>. Kubelet identity gets AcrPull.
# Tag immutability is enforced per repository by the deploy workflow (`az acr repository update
# --write-enabled false`) because ACR locks are repository-level, not registry-level.
terraform {
  required_version = ">= 1.8"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = ">= 4.0, < 6.0" }
    random  = { source = "hashicorp/random", version = ">= 3.6" }
  }
}

locals {
  rg        = var.context.scope
  tags      = merge(var.context.tags, { service = "platform", module = "registry" })
  namespace = "afrotransact"
  premium   = var.context.environment == "prod"
  name      = substr(lower(replace("${var.context.name}${random_id.suffix.hex}", "/[^a-z0-9]/", "")), 0, 50)
}

resource "random_id" "suffix" {
  byte_length = 2
}

resource "azurerm_container_registry" "this" {
  name                          = local.name
  resource_group_name           = local.rg
  location                      = var.context.region
  sku                           = local.premium ? "Premium" : "Standard"
  admin_enabled                 = false
  anonymous_pull_enabled        = false
  public_network_access_enabled = true
  zone_redundancy_enabled       = local.premium
  retention_policy_in_days      = local.premium ? 30 : null
  tags                          = local.tags
}

resource "azurerm_role_assignment" "pull" {
  for_each             = toset(var.reader_principals)
  scope                = azurerm_container_registry.this.id
  role_definition_name = "AcrPull"
  principal_id         = each.value
}

# Keep the newest N tags per repository (runs inside ACR Tasks, daily).
resource "azurerm_container_registry_task" "purge" {
  name                  = "purge-old-images"
  container_registry_id = azurerm_container_registry.this.id
  platform {
    os = "Linux"
  }
  encoded_step {
    task_content = base64encode(<<-EOT
      version: v1.1.0
      steps:
        - cmd: acr purge --filter '${local.namespace}/.*:.*' --ago 30d --keep ${var.keep_last} --untagged
          disableWorkingDirectoryOverride: true
          timeout: 3600
    EOT
    )
  }
  timer_trigger {
    name     = "daily"
    schedule = "0 3 * * *"
    enabled  = true
  }
  tags = merge(local.tags, { immutable_tags = tostring(var.immutable_tags) })
}
