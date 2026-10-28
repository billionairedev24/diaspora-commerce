# Azure GitHub OIDC trust: a user-assigned managed identity with one federated credential per allowed
# `sub` (issuer token.actions.githubusercontent.com). azure/login uses client-id + tenant-id +
# subscription-id - no client secret anywhere. No Entra app registration (and no Graph permissions) needed.
#   deploy -> AcrPush on the platform registry
#   plan   -> Reader on the subscription
#   apply  -> Contributor + Role Based Access Control Administrator on the subscription
terraform {
  required_version = ">= 1.8"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = ">= 4.0, < 6.0" }
  }
}

data "azurerm_client_config" "this" {}

locals {
  rg           = var.context.scope
  tags         = merge(var.context.tags, { service = "ci", module = "github-oidc" })
  subscription = "/subscriptions/${data.azurerm_client_config.this.subscription_id}"
  subjects     = { for s in var.subjects : substr(replace(s, "/[^a-zA-Z0-9-]/", "-"), 0, 120) => s if !strcontains(s, "*") }
  roles = {
    deploy = []
    plan   = ["Reader"]
    apply  = ["Contributor", "Role Based Access Control Administrator"]
  }
}

resource "azurerm_user_assigned_identity" "this" {
  name                = "${var.context.name}-github-${var.access}"
  location            = var.context.region
  resource_group_name = local.rg
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "this" {
  for_each                  = local.subjects
  name                      = each.key
  user_assigned_identity_id = azurerm_user_assigned_identity.this.id
  issuer                    = "https://token.actions.githubusercontent.com"
  subject                   = each.value
  audience                  = ["api://AzureADTokenExchange"]
}

resource "azurerm_role_assignment" "push" {
  count                = var.access == "deploy" && var.registry != null ? 1 : 0
  scope                = var.registry.id
  role_definition_name = "AcrPush"
  principal_id         = azurerm_user_assigned_identity.this.principal_id
}

resource "azurerm_role_assignment" "infra" {
  for_each             = toset(local.roles[var.access])
  scope                = local.subscription
  role_definition_name = each.value
  principal_id         = azurerm_user_assigned_identity.this.principal_id
}
