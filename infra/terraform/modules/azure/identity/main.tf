# Azure identity: one user-assigned managed identity per service with a federated credential trusting
# system:serviceaccount:<ns>:<sa> from the AKS OIDC issuer. Grants: Storage Blob Data Contributor on the
# listed containers; Key Vault Secrets User for External Secrets Operator.
terraform {
  required_version = ">= 1.8"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = ">= 4.0, < 6.0" }
  }
}

locals {
  rg   = var.context.scope
  tags = merge(var.context.tags, { module = "identity" })
  sa   = { for k, v in var.services : k => coalesce(v.service_account, k) }
  bucket_grants = merge([for svc, v in var.services : {
    for b in v.buckets : "${svc}/${b}" => { svc = svc, scope = var.buckets[b].id }
  }]...)
}

resource "azurerm_user_assigned_identity" "this" {
  for_each            = var.services
  name                = "${var.context.name}-${each.key}"
  location            = var.context.region
  resource_group_name = local.rg
  tags                = merge(local.tags, { service = each.key })
}

resource "azurerm_federated_identity_credential" "this" {
  for_each                  = var.services
  name                      = "kubernetes"
  user_assigned_identity_id = azurerm_user_assigned_identity.this[each.key].id
  issuer                    = var.cluster.oidc_issuer_url
  subject                   = "system:serviceaccount:${each.value.namespace}:${local.sa[each.key]}"
  audience                  = ["api://AzureADTokenExchange"]
}

resource "azurerm_role_assignment" "buckets" {
  for_each             = local.bucket_grants
  scope                = each.value.scope
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = azurerm_user_assigned_identity.this[each.value.svc].principal_id
}

resource "azurerm_role_assignment" "secrets" {
  for_each             = { for k, v in var.services : k => v if v.secrets }
  scope                = var.secret_store.key_vault_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = azurerm_user_assigned_identity.this[each.key].principal_id
}
