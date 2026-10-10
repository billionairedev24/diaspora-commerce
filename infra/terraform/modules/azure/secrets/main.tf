# Azure secrets: one Key Vault per environment (RBAC mode, purge protection, soft delete), one secret per
# service named <prefix>-<env>-<service> (Key Vault names cannot contain "/") with a JSON value.
# Network: default Deny with AzureServices bypass; context.admin_cidrs (CI runners/VPN) may reach the data
# plane so Terraform can seed secrets; workloads read through ESO via the vault private endpoint.
terraform {
  required_version = ">= 1.8"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = ">= 4.0, < 6.0" }
    random  = { source = "hashicorp/random", version = ">= 3.6" }
  }
}

data "azurerm_client_config" "this" {}

locals {
  rg     = var.context.scope
  prefix = "${var.prefix}-${var.context.environment}"
  tags   = merge(var.context.tags, { module = "secrets" })
  # 3-24 chars, alphanumerics and dashes.
  vault_name = substr("${replace(var.context.name, "/[^a-zA-Z0-9-]/", "")}-${random_id.suffix.hex}", 0, 24)
}

resource "random_id" "suffix" {
  byte_length = 2
}

resource "azurerm_key_vault" "this" {
  name                          = local.vault_name
  location                      = var.context.region
  resource_group_name           = local.rg
  tenant_id                     = data.azurerm_client_config.this.tenant_id
  sku_name                      = "standard"
  rbac_authorization_enabled    = true
  purge_protection_enabled      = var.context.deletion_protection
  soft_delete_retention_days    = 30
  public_network_access_enabled = true
  tags                          = merge(local.tags, { service = "platform" })

  network_acls {
    default_action = length(var.context.admin_cidrs) > 0 ? "Deny" : "Allow"
    bypass         = "AzureServices"
    ip_rules       = var.context.admin_cidrs
  }
}

# The identity running Terraform manages secret values.
resource "azurerm_role_assignment" "terraform" {
  scope                = azurerm_key_vault.this.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.this.object_id
}

resource "azurerm_key_vault_secret" "service" {
  for_each     = toset(var.services)
  name         = "${local.prefix}-${each.key}"
  key_vault_id = azurerm_key_vault.this.id
  content_type = "application/json"
  value        = jsonencode(lookup(var.initial_values, each.key, {}))
  tags         = merge(local.tags, { service = each.key })
  lifecycle {
    ignore_changes = [value]
  }
  depends_on = [azurerm_role_assignment.terraform]
}
