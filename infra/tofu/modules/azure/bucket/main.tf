# Azure buckets: one StorageV2 account per environment with one private container per bucket key.
# Shared keys disabled (Entra ID only), no anonymous/nested public access, HTTPS + TLS 1.2 only,
# infrastructure (double) encryption, public network off with a blob private endpoint, versioning and
# soft delete, container-level time-based immutability (locked when deletion_protection) for KYC evidence,
# lifecycle to Cool/Cold/Archive. Containers are created through ARM (storage_account_id) so Terraform
# never needs data-plane network access.
terraform {
  required_version = ">= 1.8"
  required_providers {
    azurerm = { source = "hashicorp/azurerm", version = ">= 4.0, < 6.0" }
    random  = { source = "hashicorp/random", version = ">= 3.6" }
  }
}

locals {
  rg   = var.context.scope
  tags = merge(var.context.tags, { service = "platform", module = "bucket" })
  # 3-24 lowercase alphanumerics, globally unique.
  account_name   = substr(lower(replace("${var.context.name}${random_id.suffix.hex}", "/[^a-z0-9]/", "")), 0, 24)
  any_versioning = anytrue([for b in values(var.buckets) : b.versioning || b.object_lock])
  cors_origins   = distinct(flatten([for b in values(var.buckets) : b.cors_origins]))
}

resource "random_id" "suffix" {
  byte_length = 3
}

resource "azurerm_storage_account" "this" {
  name                              = local.account_name
  resource_group_name               = local.rg
  location                          = var.context.region
  account_kind                      = "StorageV2"
  account_tier                      = "Standard"
  account_replication_type          = var.context.environment == "prod" ? "ZRS" : "LRS"
  access_tier                       = "Hot"
  https_traffic_only_enabled        = true
  min_tls_version                   = "TLS1_2"
  allow_nested_items_to_be_public   = false
  shared_access_key_enabled         = false
  default_to_oauth_authentication   = true
  infrastructure_encryption_enabled = true
  public_network_access_enabled     = var.network == null
  tags                              = local.tags

  # Default deny; workloads use the blob private endpoint, Terraform uses ARM (storage_account_id).
  network_rules {
    default_action = "Deny"
    bypass         = ["AzureServices", "Logging", "Metrics"]
    ip_rules       = var.context.admin_cidrs
  }

  blob_properties {
    versioning_enabled = local.any_versioning
    delete_retention_policy {
      days = 30
    }
    container_delete_retention_policy {
      days = 30
    }
    dynamic "cors_rule" {
      for_each = length(local.cors_origins) > 0 ? [1] : []
      content {
        allowed_methods    = ["GET", "PUT", "HEAD"]
        allowed_origins    = local.cors_origins
        allowed_headers    = ["*"]
        exposed_headers    = ["ETag"]
        max_age_in_seconds = 3600
      }
    }
  }
}

resource "azurerm_storage_container" "this" {
  for_each              = var.buckets
  name                  = each.key
  storage_account_id    = azurerm_storage_account.this.id
  container_access_type = "private"
}

resource "azurerm_storage_container_immutability_policy" "this" {
  for_each                              = { for k, v in var.buckets : k => v if v.object_lock }
  storage_container_resource_manager_id = azurerm_storage_container.this[each.key].id
  immutability_period_in_days           = max(each.value.retention_days, 1)
  protected_append_writes_all_enabled   = false
  locked                                = var.context.deletion_protection
}

resource "azurerm_storage_management_policy" "this" {
  count              = length([for v in values(var.buckets) : v if v.archive_after_days > 0 || v.expire_after_days > 0]) > 0 ? 1 : 0
  storage_account_id = azurerm_storage_account.this.id

  dynamic "rule" {
    for_each = { for k, v in var.buckets : k => v if v.archive_after_days > 0 || v.expire_after_days > 0 }
    content {
      name    = "tier-${rule.key}"
      enabled = true
      filters {
        blob_types   = ["blockBlob"]
        prefix_match = ["${rule.key}/"]
      }
      actions {
        base_blob {
          tier_to_cool_after_days_since_modification_greater_than    = rule.value.archive_after_days > 0 ? rule.value.archive_after_days : null
          tier_to_archive_after_days_since_modification_greater_than = rule.value.archive_after_days > 0 ? rule.value.archive_after_days + 90 : null
          delete_after_days_since_modification_greater_than          = rule.value.expire_after_days > 0 ? rule.value.expire_after_days : null
        }
      }
    }
  }
}

resource "azurerm_private_endpoint" "blob" {
  count               = var.network == null ? 0 : 1
  name                = "${local.account_name}-blob"
  location            = var.context.region
  resource_group_name = local.rg
  subnet_id           = var.network.endpoint_subnet_id
  tags                = local.tags

  private_service_connection {
    name                           = "${local.account_name}-blob"
    private_connection_resource_id = azurerm_storage_account.this.id
    subresource_names              = ["blob"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "blob"
    private_dns_zone_ids = [var.network.private_dns_zone_ids["blob"]]
  }
}

resource "azurerm_management_lock" "this" {
  count      = var.context.deletion_protection ? 1 : 0
  name       = "${local.account_name}-cannot-delete"
  scope      = azurerm_storage_account.this.id
  lock_level = "CanNotDelete"
  notes      = "Holds KYC evidence and exports."
}
